package com.thecakeisalie.mccp;

import com.thecakeisalie.mccp.navigation.NavigationTerrain;
import com.thecakeisalie.mccp.navigation.TraversalEdge;
import com.thecakeisalie.mccp.navigation.TraversalMode;
import com.thecakeisalie.mccp.navigation.TraversalRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
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
 * Bounded incremental A* over a PLUGGABLE set of directed movement edges.
 *
 * The graph is implicit: registered TraversalModes provide candidate edges,
 * per-mode safety rules and execution. This class is agnostic to ladders,
 * walking, elevators or future mod transport. All world access is on the
 * server tick thread; unknown/unloaded terrain is never loaded implicitly.
 */
public final class RoutePlanner {
    public static final int MAX_HORIZONTAL = 48;
    public static final int MAX_VERTICAL = 16;
    public static final int MAX_EXPANDED = 6500;
    public static final int MAX_PATH_NODES = 240;
    private static final int MIN_SEGMENT_BLOCKS = 4;
    private static final double MIN_HEURISTIC_IMPROVEMENT = 2.0;

    /** The route consists of mode-tagged edges, not just voxel coordinates. */
    public record Plan(List<TraversalEdge> edges, String reason, int expanded) {
        public boolean found() { return "found".equals(reason); }
        public boolean partial() { return "segment".equals(reason); }
        public boolean usable() { return found() || partial(); }
        /** Compatible coordinate summary for UI/status consumers. */
        public List<BlockPos> nodes() {
            if (edges.isEmpty()) return List.of();
            ArrayList<BlockPos> locations = new ArrayList<>();
            locations.add(edges.get(0).from());
            for (TraversalEdge edge : edges) locations.add(edge.to());
            return List.copyOf(locations);
        }
    }
    private record Entry(BlockPos pos, double cost, double estimate) {}
    private record Traced(List<TraversalEdge> edges, boolean truncated) {}

    private final BlockPos start;
    private final Vec3 target;
    private final boolean targetIsPlayer;
    private final NavigationTerrain terrain;
    private final List<TraversalMode> modes;
    private final Set<BlockPos> rejectedEndPoints;
    private final PriorityQueue<Entry> frontier = new PriorityQueue<>(
        Comparator.comparingDouble(Entry::estimate)
            .thenComparingDouble(Entry::cost)
            .thenComparingInt(e -> e.pos().getX())
            .thenComparingInt(e -> e.pos().getY())
            .thenComparingInt(e -> e.pos().getZ()));
    private final Map<BlockPos, Double> costs = new HashMap<>();
    private final Map<BlockPos, TraversalEdge> parents = new HashMap<>();
    private final Set<BlockPos> visited = new HashSet<>();
    private final double startHeuristic;
    private double bestScore = Double.POSITIVE_INFINITY;
    private BlockPos best;
    private int expanded;
    private boolean initialized;
    private Plan finished;

    public RoutePlanner(ServerLevel level, BlockPos start, Vec3 target, boolean targetIsPlayer) {
        this(level, null, start, target, targetIsPlayer, Set.of());
    }
    public RoutePlanner(ServerLevel level, CompanionEntity avatar, BlockPos start,
                        Vec3 target, boolean targetIsPlayer) {
        this(level, avatar, start, target, targetIsPlayer, Set.of());
    }

    public RoutePlanner(ServerLevel level, CompanionEntity avatar, BlockPos start,
                        Vec3 target, boolean targetIsPlayer, Set<BlockPos> rejectedEndPoints) {
        this.terrain = new NavigationTerrain(level, avatar);
        this.start = start;
        this.target = target;
        this.targetIsPlayer = targetIsPlayer;
        this.rejectedEndPoints = Set.copyOf(rejectedEndPoints);
        this.modes = TraversalRegistry.modes();
        this.startHeuristic = heuristic(start);
    }

    /** Null means planning is incomplete; budgets cap tick-thread work. */
    public Plan step(int nodeBudget, long nanosBudget) {
        if (finished != null) return finished;
        if (!initialized) {
            initialized = true;
            if (!inside(start)) return finish(List.of(), "out_of_search_range");
            if (!terrain.standable(start)) return finish(List.of(), "unsafe_start");
            costs.put(start, 0.0);
            frontier.add(new Entry(start, 0.0, startHeuristic));
        }
        long deadline = System.nanoTime() + Math.max(100_000L, nanosBudget);
        int operations = 0;
        while (!frontier.isEmpty() && expanded < MAX_EXPANDED
                && operations < nodeBudget && System.nanoTime() < deadline) {
            Entry cur = frontier.remove();
            if (visited.contains(cur.pos())
                    || cur.cost() > costs.getOrDefault(cur.pos(), Double.POSITIVE_INFINITY) + 0.001)
                continue;
            visited.add(cur.pos());
            expanded++;
            operations++;
            if (goal(cur.pos())) {
                Traced trace = trace(cur.pos());
                return finish(trace.edges(), trace.truncated() ? "segment" : "found");
            }
            updateBest(cur);
            for (TraversalMode mode : modes) {
                mode.expand(terrain, cur.pos(), edge -> {
                    if (edge == null || !edge.from().equals(cur.pos())
                            || edge.mode() != mode || !inside(edge.to())
                            || visited.contains(edge.to())) return;
                    // Treat expansions as proposals. Even untrusted mode
                    // implementations must pass their own safety verifier.
                    if (!mode.valid(terrain, edge.from(), edge.to())) return;
                    double candidate = cur.cost() + edge.cost();
                    if (candidate + 0.001 >= costs.getOrDefault(edge.to(),
                            Double.POSITIVE_INFINITY)) return;
                    costs.put(edge.to(), candidate);
                    parents.put(edge.to(), edge);
                    frontier.add(new Entry(edge.to(), candidate, candidate + heuristic(edge.to())));
                });
            }
        }
        if (!frontier.isEmpty() && expanded < MAX_EXPANDED) return null;
        if (best != null) {
            Traced trace = trace(best);
            return finish(trace.edges(), "segment");
        }
        return finish(List.of(), expanded >= MAX_EXPANDED ? "search_budget_exhausted"
            : terrain.unloaded() ? "unloaded_chunks"
            : terrain.unsafe() ? "blocked_or_hazardous" : "no_safe_route_within_bounds");
    }

    private void updateBest(Entry candidate) {
        if (rejectedEndPoints.contains(candidate.pos())) return;
        double improvement = startHeuristic - heuristic(candidate.pos());
        double travelled = Math.sqrt(candidate.pos().distSqr(start));
        if (improvement < MIN_HEURISTIC_IMPROVEMENT || travelled < MIN_SEGMENT_BLOCKS)
            return;
        double score = heuristic(candidate.pos()) + candidate.cost() / 4.0;
        if (score + 0.001 < bestScore) {
            best = candidate.pos();
            bestScore = score;
        }
    }

    private Traced trace(BlockPos finish) {
        ArrayList<TraversalEdge> reversed = new ArrayList<>();
        BlockPos current = finish;
        while (!current.equals(start) && reversed.size() <= MAX_EXPANDED + 1) {
            TraversalEdge edge = parents.get(current);
            if (edge == null) return new Traced(List.of(), false);
            reversed.add(edge);
            current = edge.from();
        }
        if (!current.equals(start)) return new Traced(List.of(), false);
        java.util.Collections.reverse(reversed);
        if (reversed.size() >= MAX_PATH_NODES) {
            return new Traced(List.copyOf(reversed.subList(0, MAX_PATH_NODES - 1)), true);
        }
        return new Traced(List.copyOf(reversed), false);
    }

    private Plan finish(List<TraversalEdge> edges, String reason) {
        if (edges.isEmpty() && "segment".equals(reason)) reason = "path_too_long";
        finished = new Plan(edges, reason, expanded);
        return finished;
    }

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
        double dx = pos.getX() + 0.5 - target.x;
        double dz = pos.getZ() + 0.5 - target.z;
        double dy = Math.abs(pos.getY() - target.y);
        return Math.max(0, Math.hypot(dx, dz) - (targetIsPlayer ? 2.75 : 0.8)) + dy * 1.2;
    }

    private boolean inside(BlockPos pos) {
        return Math.abs(pos.getX() - start.getX()) <= MAX_HORIZONTAL
            && Math.abs(pos.getZ() - start.getZ()) <= MAX_HORIZONTAL
            && Math.abs(pos.getY() - start.getY()) <= MAX_VERTICAL
            && pos.getY() > terrain.level().getMinBuildHeight() + 1
            && pos.getY() + 2 < terrain.level().getMaxBuildHeight();
    }

    // Compatibility utility methods for other MCCP systems.
    public static boolean isOpenableDoor(BlockState state) {
        return NavigationTerrain.woodenDoor(state);
    }
    public static boolean isHazard(BlockState state) {
        return NavigationTerrain.hazard(state);
    }
}
