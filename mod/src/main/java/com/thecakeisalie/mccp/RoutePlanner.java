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

    public record Plan(List<BlockPos> nodes, String reason, int expanded) {
        public boolean found() { return "found".equals(reason); }
    }
    private record Entry(BlockPos pos, double cost, double estimate) {}

    private final ServerLevel level;
    private final BlockPos start;
    private final Vec3 target;
    private final boolean targetIsPlayer;
    private final Map<BlockPos, Boolean> freeCache = new HashMap<>();
    private boolean unloaded;
    private boolean unsafe;

    public RoutePlanner(ServerLevel level, BlockPos start, Vec3 target, boolean targetIsPlayer) {
        this.level = level;
        this.start = start;
        this.target = target;
        this.targetIsPlayer = targetIsPlayer;
    }

    public Plan solve() {
        if (!inside(start)) return new Plan(List.of(), "out_of_search_range", 0);
        // We don't infer new chunks by forcing them to load. Safety first.
        if (!level.hasChunkAt(start)) return new Plan(List.of(), "unloaded_start", 0);
        if (!safeNode(start)) return new Plan(List.of(), "unsafe_start", 0);
        PriorityQueue<Entry> frontier = new PriorityQueue<>(
            Comparator.comparingDouble(Entry::estimate));
        Map<BlockPos, Double> cost = new HashMap<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        cost.put(start, 0.0);
        frontier.add(new Entry(start, 0, heuristic(start)));
        int expanded = 0;
        while (!frontier.isEmpty()) {
            Entry cur = frontier.remove();
            if (!visited.add(cur.pos())) continue;
            expanded++;
            if (goal(cur.pos())) {
                List<BlockPos> result = new ArrayList<>();
                BlockPos node = cur.pos();
                while (node != null && result.size() < MAX_PATH_NODES) {
                    result.add(node);
                    node = parent.get(node);
                }
                if (node != null) return new Plan(List.of(), "path_too_long", expanded);
                java.util.Collections.reverse(result);
                return new Plan(List.copyOf(result), "found", expanded);
            }
            if (expanded >= MAX_EXPANDED)
                return new Plan(List.of(), "search_budget_exhausted", expanded);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos lateral = cur.pos().relative(direction);
                // A step up, level step, or up to two-block safe descent.
                for (int dy : new int[]{0, 1, -1, -2}) {
                    BlockPos next = lateral.offset(0, dy, 0);
                    if (!inside(next) || !safeNode(next)) continue;
                    if (!canTraverse(cur.pos(), next)) continue;
                    double nextCost = cur.cost() + (dy == 0 ? 1.0 : 1.7);
                    if (nextCost >= cost.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                    cost.put(next, nextCost);
                    parent.put(next, cur.pos());
                    frontier.add(new Entry(next, nextCost, nextCost + heuristic(next)));
                }
            }
            if (isClimbable(cur.pos()) || isClimbable(cur.pos().below())) {
                for (int dy : new int[]{1, -1}) {
                    BlockPos next = cur.pos().above(dy);
                    if (!inside(next) || !safeNode(next)) continue;
                    if (!isClimbable(next) && !isClimbable(cur.pos())) continue;
                    double nextCost = cur.cost() + 1.35;
                    if (nextCost >= cost.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                    cost.put(next, nextCost);
                    parent.put(next, cur.pos());
                    frontier.add(new Entry(next, nextCost, nextCost + heuristic(next)));
                }
            }
        }
        if (unloaded) return new Plan(List.of(), "unloaded_chunks", expanded);
        if (unsafe) return new Plan(List.of(), "blocked_or_hazardous", expanded);
        return new Plan(List.of(), "no_safe_route_within_bounds", expanded);
    }

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
        if (hazard(feetState) || hazard(headState) || hazard(floorState)) {
            unsafe = true;
            return false;
        }
        // Ladder column can suspend the companion without solid support.
        if (isClimbable(pos) || isClimbable(floor)) return true;
        return !floorState.getCollisionShape(level, floor).isEmpty();
    }

    private boolean passable(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (hazard(state)) return false;
        if (!level.getFluidState(pos).isEmpty()) return false;
        if (state.getCollisionShape(level, pos).isEmpty()) return true;
        return isOpenableDoor(state);
    }

    public static boolean isOpenableDoor(BlockState state) {
        return state.getBlock() instanceof DoorBlock
            && state.is(BlockTags.WOODEN_DOORS);
    }

    private boolean hazard(BlockState state) {
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
