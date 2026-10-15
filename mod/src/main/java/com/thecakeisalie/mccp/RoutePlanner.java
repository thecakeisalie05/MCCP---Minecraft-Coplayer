package com.thecakeisalie.mccp;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A bounded, deterministic A* search over the LOADED voxel neighborhood.
 *
 * Excludes dangerous fall edges, unknown chunks, damaging blocks, liquids,
 * impassable iron doors, and unsupported vertical transitions. Wooden doors
 * are considered passable because SmartNavigation opens them near the avatar.
 * Ladders are vertical edges with separate execution. Closed trapdoors count
 * as solid footing only; open trapdoors are collision-checked.
 *
 * A failure is NOT a global proof of unreachable terrain: unloaded chunks,
 * modded collision behavior, search bounds and changing worlds limit the claim.
 */
public final class RoutePlanner {
    public static final int MAX_HORIZONTAL = 48;
    public static final int MAX_VERTICAL = 16;
    public static final int MAX_EXPANDED = 6500;
    public static final int MAX_PATH_NODES = 240;

    /** "found" reaches the goal; "segment" safely advances towards it. */
    public record Plan(List<BlockPos> nodes, String reason, int expanded) {
        public boolean found() { return "found".equals(reason); }
        public boolean partial() { return "segment".equals(reason); }
        public boolean usable() { return found() || partial(); }
    }

    /** A queue entry is immutable, so stale estimates are discarded on removal. */
    private record Entry(BlockPos pos, double cost, double estimate) {}

    private static final int MIN_SEGMENT_BLOCKS = 4;
    private static final double MIN_HEURISTIC_IMPROVEMENT = 2.0;
    private final ServerLevel level;
    private final BlockPos start;
    private final Vec3 target;
    private final boolean targetIsPlayer;
    private final Map<BlockPos, Boolean> freeCache = new HashMap<>();
    private final PriorityQueue<Entry> frontier = new PriorityQueue<>(
        Comparator.comparingDouble(Entry::estimate)
            .thenComparingDouble(Entry::cost)
            .thenComparingInt(e -> e.pos().getX())
            .thenComparingInt(e -> e.pos().getY())
            .thenComparingInt(e -> e.pos().getZ())
    );
    private final Map<BlockPos, Double> cost = new HashMap<>();
    private final Map<BlockPos, BlockPos> parent = new HashMap<>();
    private final Set<BlockPos> visited = new HashSet<>();
    private boolean initialized;
    private boolean unloaded;
    private boolean unsafe;
    private int expanded;
    private Plan finished;
    private BlockPos best;
    private double bestScore = Double.POSITIVE_INFINITY;
    private final double startHeuristic;
    private final Set<BlockPos> rejectedEndPoints;

    public RoutePlanner(ServerLevel level, BlockPos start, Vec3 target, boolean targetIsPlayer) {
        this(level, start, target, targetIsPlayer, Set.of());
    }

    public RoutePlanner(ServerLevel level, BlockPos start, Vec3 target,
                        boolean targetIsPlayer, Set<BlockPos> rejectedEndPoints) {
        this.level = level;
        this.start = start;
        this.target = target;
        this.targetIsPlayer = targetIsPlayer;
        this.rejectedEndPoints = Set.copyOf(rejectedEndPoints);
        this.startHeuristic = heuristic(start);
    }

    /**
     * Incrementally advance A*. Intended ONLY for the Minecraft server thread.
     * Returns null while still searching; each tick has both node and wall-time
     * limits to protect TPS. Does not access unloaded chunks.
     */
    public Plan step(int nodeBudget, long nanosBudget) {
        if (finished != null) return finished;
        if (!initialized) {
            initialized = true;
            if (!inside(start)) return finish(List.of(), "out_of_search_range");
            if (!level.hasChunkAt(start)) return finish(List.of(), "unloaded_start");
            if (!safeNode(start)) return finish(List.of(), "unsafe_start");
            cost.put(start, 0.0);
            frontier.add(new Entry(start, 0, startHeuristic));
        }
        long deadline = System.nanoTime() + Math.max(100_000L, nanosBudget);
        int work = 0;
        while (!frontier.isEmpty() && expanded < MAX_EXPANDED
                && work < nodeBudget && System.nanoTime() < deadline) {
            Entry cur = frontier.remove();
            if (visited.contains(cur.pos())
                    || cur.cost() > cost.getOrDefault(cur.pos(), Double.POSITIVE_INFINITY) + 0.001) {
                continue;
            }
            visited.add(cur.pos());
            expanded++;
            work++;
            if (goal(cur.pos())) {
                List<BlockPos> path = trace(cur.pos());
                return finish(path, !path.isEmpty() && path.get(path.size() - 1).equals(cur.pos())
                    ? "found" : "segment");
            }
            updateBest(cur);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos lateral = cur.pos().relative(direction);
                for (int dy : new int[]{0, 1, -1, -2}) {
                    BlockPos next = lateral.offset(0, dy, 0);
                    if (!inside(next) || !safeNode(next) || !canTraverse(cur.pos(), next)) continue;
                    relax(cur, next, dy == 0 ? 1.0 : (dy > 0 ? 1.9 : 1.5 + (-dy - 1) * 0.4));
                }
            }
            // Baritone also evaluates diagonals. For the server-owned MCCP
            // entity we only allow diagonals when BOTH adjacent cardinal
            // body columns are traversable, avoiding corner clipping.
            for (int dx : new int[]{-1, 1}) {
                for (int dz : new int[]{-1, 1}) {
                    BlockPos sideX = cur.pos().offset(dx, 0, 0);
                    BlockPos sideZ = cur.pos().offset(0, 0, dz);
                    BlockPos next = cur.pos().offset(dx, 0, dz);
                    if (!inside(next) || !safeNode(next)
                            || !safeNode(sideX) || !safeNode(sideZ)) continue;
                    relax(cur, next, Math.sqrt(2));
                }
            }
            if (isClimbable(cur.pos()) || isClimbable(cur.pos().below())) {
                for (int dy : new int[]{1, -1}) {
                    BlockPos next = cur.pos().offset(0, dy, 0);
                    if (!inside(next) || !safeNode(next)) continue;
                    if (!isClimbable(next) && !isClimbable(cur.pos())) continue;
                    relax(cur, next, 1.6);
                }
            }
        }
        if (!frontier.isEmpty() && expanded < MAX_EXPANDED) return null;
        // Baritone-inspired idea: use the best *reachable* progress found by
        // A* if the destination is outside the current search region.
        // Unlike a geometric 32-block waypoint, this has a verified parent path.
        if (best != null) return finish(trace(best), "segment");
        return finish(List.of(), expanded >= MAX_EXPANDED ? "search_budget_exhausted"
            : unloaded ? "unloaded_chunks"
            : unsafe ? "blocked_or_hazardous" : "no_safe_route_within_bounds");
    }

    private void relax(Entry current, BlockPos next, double movementCost) {
        if (visited.contains(next)) return;
        double newCost = current.cost() + movementCost;
        if (newCost + 0.001 >= cost.getOrDefault(next, Double.POSITIVE_INFINITY)) return;
        cost.put(next, newCost);
        parent.put(next, current.pos());
        frontier.add(new Entry(next, newCost, newCost + heuristic(next)));
    }

    private void updateBest(Entry candidate) {
        if (rejectedEndPoints.contains(candidate.pos())) return;
        double improvement = startHeuristic - heuristic(candidate.pos());
        double distance = Math.sqrt(candidate.pos().distSqr(start));
        if (improvement < MIN_HEURISTIC_IMPROVEMENT || distance < MIN_SEGMENT_BLOCKS) return;
        // Prefer approaching the goal, but penalize extravagant detours.
        // We keep searching even when first progress is found.
        double score = heuristic(candidate.pos()) + candidate.cost() / 4.0;
        if (score + 0.001 < bestScore) {
            bestScore = score;
            best = candidate.pos();
        }
    }

    private List<BlockPos> trace(BlockPos last) {
        ArrayList<BlockPos> path = new ArrayList<>();
        BlockPos cursor = last;
        while (cursor != null && path.size() <= MAX_EXPANDED + 1) {
            path.add(cursor);
            cursor = parent.get(cursor);
        }
        if (cursor != null) return List.of();
        java.util.Collections.reverse(path);
        // Very long routes are broken at a safe *prefix* from the avatar.
        // The suffix will be recalculated from the actual endpoint.
        if (path.size() > MAX_PATH_NODES)
            return List.copyOf(path.subList(0, MAX_PATH_NODES));
        return List.copyOf(path);
    }

    private Plan finish(List<BlockPos> nodes, String reason) {
        if (nodes.isEmpty() && ("found".equals(reason) || "segment".equals(reason)))
            reason = "path_too_long";
        finished = new Plan(nodes, reason, expanded);
        return finished;
    }

    /** Returns a single reproducible result; no implicit full-world search. */
    public Plan result() { return finished; }
    public int expanded() { return expanded; }

    private boolean goal(BlockPos pos) {
        double dx = pos.getX() + 0.5 - target.x;
        double dz = pos.getZ() + 0.5 - target.z;
        double dy = pos.getY() - target.y;
        if (targetIsPlayer) return dx * dx + dz * dz <= 2.75 * 2.75 && Math.abs(dy) <= 1.3;
        return dx * dx + dz * dz <= 0.8 * 0.8 && Math.abs(dy) <= 1.1;
    }

    private double heuristic(BlockPos pos) {
        double dx = Math.abs(pos.getX() + 0.5 - target.x);
        double dz = Math.abs(pos.getZ() + 0.5 - target.z);
        double dy = Math.abs(pos.getY() - target.y);
        double horizontal = Math.max(0, Math.sqrt(dx * dx + dz * dz) - (targetIsPlayer ? 2.75 : 0.8));
        return horizontal + dy * 1.2;
    }

    private boolean inside(BlockPos pos) {
        return Math.abs(pos.getX() - start.getX()) <= MAX_HORIZONTAL
            && Math.abs(pos.getZ() - start.getZ()) <= MAX_HORIZONTAL
            && Math.abs(pos.getY() - start.getY()) <= MAX_VERTICAL
            && pos.getY() > level.getMinBuildHeight() + 1
            && pos.getY() + 2 < level.getMaxBuildHeight();
    }

    public boolean safeNode(BlockPos pos) {
        Boolean cached = freeCache.get(pos);
        if (cached != null) return cached;
        boolean safe = validateNode(pos);
        freeCache.put(pos, safe);
        return safe;
    }

    private boolean validateNode(BlockPos pos) {
        if (!level.hasChunkAt(pos)) { unloaded = true; return false; }
        BlockPos head = pos.above();
        BlockPos floor = pos.below();
        if (!level.hasChunkAt(head) || !level.hasChunkAt(floor)) {
            unloaded = true;
            return false;
        }
        if (!passable(pos) || !passable(head)) { unsafe = true; return false; }
        BlockState feetState = level.getBlockState(pos);
        BlockState headState = level.getBlockState(head);
        BlockState floorState = level.getBlockState(floor);
        if (isHazard(feetState) || isHazard(headState) || isHazard(floorState)) {
            unsafe = true;
            return false;
        }
        // Ladder column can suspend the companion without solid support.
        if (isClimbable(pos) || isClimbable(floor)) return true;
        return !floorState.getCollisionShape(level, floor).isEmpty();
    }

    private boolean passable(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (isHazard(state)) return false;
        if (!level.getFluidState(pos).isEmpty()) return false;
        if (state.getCollisionShape(level, pos).isEmpty()) return true;
        return isOpenableDoor(state);
    }

    public static boolean isOpenableDoor(BlockState state) {
        return state.getBlock() instanceof DoorBlock
            && state.is(BlockTags.WOODEN_DOORS);
    }

    public static boolean isHazard(BlockState state) {
        return state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK)
            || state.is(Blocks.CACTUS) || state.is(Blocks.FIRE)
            || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CAMPFIRE)
            || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.SWEET_BERRY_BUSH)
            || state.is(Blocks.POWDER_SNOW);
    }

    private boolean isClimbable(BlockPos pos) {
        return level.hasChunkAt(pos) && level.getBlockState(pos).is(BlockTags.CLIMBABLE);
    }

    private boolean canTraverse(BlockPos from, BlockPos to) {
        int dy = to.getY() - from.getY();
        // Never jump across unsupported space or an excessive drop.
        if (dy < -2 || dy > 1) return false;
        if (dy == 1 && (!passable(from.above()) || !passable(to.above()))) return false;
        if (dy < 0) {
            // Avoid passing through a ceiling or landing under an overhang.
            if (!passable(to.above())) return false;
        }
        return true;
    }
}
