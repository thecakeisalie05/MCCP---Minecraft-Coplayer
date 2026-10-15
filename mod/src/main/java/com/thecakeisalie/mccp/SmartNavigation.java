package com.thecakeisalie.mccp;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import com.thecakeisalie.mccp.navigation.NavigationTerrain;
import com.thecakeisalie.mccp.navigation.TraversalEdge;
import com.thecakeisalie.mccp.navigation.TraversalRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * MCCP's server-side autonomous navigation controller.
 *
 * Uses Baritone-informed bounded A* / partial-path continuation, but executes
 * on a PathfinderMob instead of using Baritone's player input pipeline.
 *
 * Important: all search and world access stay on the server thread. Planning
 * is split into small work budgets; no recursion, blocking worker or chunk loads.
 */
public final class SmartNavigation {
    private static final double ARRIVE = 2.75;
    private static final double RESUME = 4.5;
    private static final int REPATH_INTERVAL = 26;
    private static final int RETRY_INTERVAL = 100;
    private static final int STUCK_INTERVAL = 68;
    private static final int PLANNER_NODES_PER_TICK = 110;
    private static final long PLANNER_NANOS_PER_TICK = 1_500_000L;
    private static final int MAX_REJECTED_ENDPOINTS = 12;

    private static Session session;
    private static Probe probe;
    private static long clock;

    public enum Mode { IDLE, MOVE_TO, FOLLOW_ONCE, FOLLOW_CONTINUOUS }

    private static final class Session {
        final UUID avatar;
        final Mode mode;
        UUID targetPlayer;
        Vec3 destination;
        Vec3 plannedFor;
        RoutePlanner planner;
        List<TraversalEdge> route = List.of();
        int index;
        long nextPlan;
        long lastProgress;
        double bestWaypointDistance = Double.POSITIVE_INFINITY;
        int failedPlans;
        int completedSegments;
        int expandedNodes;
        String detail = "idle";
        boolean holding;
        boolean announcedFailure;
        boolean announcedDeparture;
        final Set<BlockPos> rejectedEnds = new HashSet<>();

        Session(CompanionEntity avatar, Mode mode) {
            this.avatar = avatar.getUUID();
            this.mode = mode;
            this.lastProgress = clock;
        }
    }

    private static final class Probe {
        final UUID avatar;
        final RoutePlanner planner;
        Probe(CompanionEntity entity, Vec3 target, boolean isPlayer) {
            avatar = entity.getUUID();
            NavigationTerrain terrain = new NavigationTerrain((ServerLevel) entity.level(), entity);
            BlockPos anchor = terrain.startAnchor(entity);
            planner = anchor == null ? null : new RoutePlanner(
                (ServerLevel) entity.level(), entity, anchor, target, isPlayer);
        }
    }

    private SmartNavigation() {}

    public static void tick(MinecraftServer server) {
        clock++;
        // Spread planning work across 10 checks/second, not an entire huge A*
        // inside a single server tick. A separate diagnostic probe shares time.
        if (clock % 2 != 0) return;
        tickProbe(server);
        Session s = session;
        if (s == null) return;
        CompanionEntity avatar = locate(server, s.avatar);
        if (avatar == null) {
            session = null;
            return;
        }

        ServerPlayer player = s.targetPlayer == null ? null
            : server.getPlayerList().getPlayer(s.targetPlayer);
        if (s.targetPlayer != null &&
                (player == null || player.level() != avatar.level())) {
            s.detail = "target_unavailable";
            if (!s.announcedFailure) speak(server, "I can't follow across dimensions or while you're offline.");
            s.announcedFailure = true;
            s.route = List.of();
            s.planner = null;
            avatar.getNavigation().stop();
            return;
        }
        Vec3 goal = player == null ? s.destination : player.position();
        if (goal == null) {
            stop(avatar);
            return;
        }
        boolean following = s.targetPlayer != null;
        double distance = avatar.position().distanceTo(goal);
        if (distance <= (following ? ARRIVE : 0.95)) {
            if (s.mode == Mode.FOLLOW_CONTINUOUS) {
                if (!s.holding) cancelActive(avatar, s);
                s.holding = true;
                s.planner = null;
                s.route = List.of();
                s.detail = "holding_near_player";
                return;
            }
            if (s.mode == Mode.FOLLOW_ONCE) speak(server, "I'm here!");
            stop(avatar);
            return;
        }
        if (s.holding && distance < RESUME) return;
        s.holding = false;

        // Discard obsolete plans when the player moves substantially.
        // Keep moving along an otherwise useful route while the target drifts
        // only a few blocks; avoid expensive one-tick replan loops.
        if (s.plannedFor != null && following
                && goal.distanceToSqr(s.plannedFor) > 8 * 8
                && clock >= s.nextPlan
                && (avatar.onGround() || new NavigationTerrain(
                    (ServerLevel) avatar.level(), avatar).climbable(avatar.blockPosition()))) {
            cancelActive(avatar, s);
            s.planner = null;
            s.route = List.of();
            s.rejectedEnds.clear();
            s.nextPlan = clock;
            s.detail = "target_moved";
        }

        if (s.planner != null) {
            RoutePlanner.Plan plan = s.planner.step(
                PLANNER_NODES_PER_TICK, PLANNER_NANOS_PER_TICK);
            s.expandedNodes = s.planner.expanded();
            if (plan != null) completePlan(server, avatar, s, plan);
            return;
        }

        if (!s.route.isEmpty() && s.index < s.route.size()) {
            advance(avatar, s);
            // An edge may finish during advance(). Never index a completed
            // route; allow the entity a few ticks to settle on its foothold.
            if (s.route.isEmpty() || s.index >= s.route.size()) return;
            int timeout = s.route.get(s.index).mode().stallTimeoutTicks();
            if (clock - s.lastProgress > Math.max(STUCK_INTERVAL, timeout)) {
                // This was a verified *geometric* segment, but actual mobility
                // didn't match the model. Avoid replaying the same endpoint.
                cancelActive(avatar, s);
                s.rejectedEnds.add(s.route.get(s.route.size() - 1).to());
                if (s.rejectedEnds.size() > MAX_REJECTED_ENDPOINTS)
                    s.rejectedEnds.clear();
                s.failedPlans++;
                s.route = List.of();
                s.detail = "movement_stalled_replanning";
                s.nextPlan = clock + 12;
                avatar.getNavigation().stop();
                if (s.failedPlans == 3)
                    speak(server, "I'm stuck on that route. I'll try a different approach.");
            }
            return;
        }

        if (clock >= s.nextPlan) beginPlan(avatar, s, goal);
    }

    private static void tickProbe(MinecraftServer server) {
        Probe current = probe;
        if (current == null) return;
        if (locate(server, current.avatar) == null) {
            probe = null;
            return;
        }
        if (current.planner == null) {
            speak(server, "I'm between footholds; let me settle before checking that route.");
            probe = null;
            return;
        }
        RoutePlanner.Plan result = current.planner.step(45, 600_000L);
        if (result == null) return;
        if (result.found()) {
            speak(server, "I verified a route to the requested destination (" +
                Math.max(0, result.nodes().size() - 1) + " steps).");
        } else if (result.partial()) {
            speak(server, "I found a safe partial route (" +
                Math.max(0, result.nodes().size() - 1) +
                " steps), but I haven't verified the whole journey.");
        } else {
            speak(server, failureText(result.reason()));
        }
        probe = null;
    }

    private static CompanionEntity locate(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) instanceof CompanionEntity avatar) return avatar;
        }
        return null;
    }

    public static ServerPlayer findPlayer(MinecraftServer server, String name,
                                          CompanionEntity avatar) {
        return server.getPlayerList().getPlayers().stream()
            .filter(p -> p.level() == avatar.level()
                && (name.isBlank() || p.getGameProfile().getName().equalsIgnoreCase(name)))
            .findFirst().orElse(null);
    }

    public static String startFollow(MinecraftServer server, CompanionEntity avatar,
                                     String name, boolean continuous) {
        ServerPlayer player = findPlayer(server, name, avatar);
        if (player == null) return "player_not_found_in_dimension";
        stop(avatar);
        Session s = new Session(avatar,
            continuous ? Mode.FOLLOW_CONTINUOUS : Mode.FOLLOW_ONCE);
        s.targetPlayer = player.getUUID();
        session = s;
        if (avatar.position().distanceTo(player.position()) <= ARRIVE) {
            if (continuous) {
                s.holding = true;
                s.detail = "holding_near_player";
                speak(server, "I'll stick with you!");
            } else {
                speak(server, "I'm already here!");
                stop(avatar);
            }
            return "near_player";
        }
        beginPlan(avatar, s, player.position());
        return continuous ? "planning_continuous_follow" : "planning_follow";
    }

    public static String moveTo(MinecraftServer server, CompanionEntity avatar, Vec3 goal) {
        if (!Double.isFinite(goal.x) || !Double.isFinite(goal.y) || !Double.isFinite(goal.z))
            return "invalid_destination";
        stop(avatar);
        Session s = new Session(avatar, Mode.MOVE_TO);
        s.destination = goal;
        session = s;
        beginPlan(avatar, s, goal);
        return "planning_move";
    }

    public static String probe(MinecraftServer server, CompanionEntity avatar,
                               Vec3 target, boolean playerTarget) {
        probe = new Probe(avatar, target, playerTarget);
        speak(server, "Checking the loaded terrain for a safe route.");
        return "route_check_started";
    }

    private static void beginPlan(CompanionEntity avatar, Session s, Vec3 target) {
        NavigationTerrain terrain = new NavigationTerrain((ServerLevel) avatar.level(), avatar);
        BlockPos anchor = terrain.startAnchor(avatar);
        if (anchor == null) {
            // Especially on stairs, the integer block under the entity's feet
            // can be SOLID while the actual body is stably supported. We must
            // not treat that as a permanent route failure. Let physics settle.
            s.planner = null;
            s.detail = "waiting_for_stable_footing";
            s.nextPlan = clock + 8;
            return;
        }
        s.planner = new RoutePlanner((ServerLevel) avatar.level(), avatar,
            anchor, target, s.targetPlayer != null, s.rejectedEnds);
        s.plannedFor = target;
        s.expandedNodes = 0;
        s.detail = "planning";
        s.nextPlan = clock + REPATH_INTERVAL;
        s.route = List.of();
        s.index = 0;
    }

    private static void completePlan(MinecraftServer server, CompanionEntity avatar,
                                     Session s, RoutePlanner.Plan plan) {
        s.planner = null;
        s.expandedNodes = plan.expanded();
        if (!plan.usable() || plan.edges().isEmpty()) {
            s.failedPlans++;
            s.route = List.of();
            s.detail = plan.reason();
            s.nextPlan = clock + RETRY_INTERVAL;
            if (!s.announcedFailure) {
                speak(server, failureText(plan.reason()) +
                    (s.mode == Mode.FOLLOW_CONTINUOUS ? " I'll check again shortly." : ""));
                s.announcedFailure = true;
            }
            if (s.mode != Mode.FOLLOW_CONTINUOUS) stop(avatar);
            return;
        }
        if (s.announcedFailure) {
            speak(server, "I found another way!");
            s.announcedFailure = false;
        }
        s.route = plan.edges();
        s.index = 0;
        s.bestWaypointDistance = Double.POSITIVE_INFINITY;
        s.lastProgress = clock;
        s.completedSegments++;
        s.detail = plan.partial() ? "traversing_partial_path" : "traversing_verified_path";
        if (!s.announcedDeparture) {
            if (s.mode == Mode.FOLLOW_CONTINUOUS)
                speak(server, "I'm coming! I'll keep following you.");
            else if (s.mode == Mode.FOLLOW_ONCE)
                speak(server, "I'm coming!");
            s.announcedDeparture = true;
        }
    }

    private static void advance(CompanionEntity avatar, Session s) {
        if (s.index >= s.route.size()) {
            s.route = List.of();
            s.nextPlan = clock + 6;
            return;
        }
        TraversalEdge edge = s.route.get(s.index);
        // Mode-tagged arrival checks let future transportation adapters define
        // station arrival without inheriting ordinary walking tolerances.
        if (edge.mode().arrived(avatar, edge.to())) {
            s.index++;
            s.bestWaypointDistance = Double.POSITIVE_INFINITY;
            s.lastProgress = clock;
            if (s.index >= s.route.size()) {
                s.route = List.of();
                s.nextPlan = clock + 6;
                return;
            }
            edge = s.route.get(s.index);
        }
        Vec3 desired = Vec3.atBottomCenterOf(edge.to());
        double distance = avatar.position().distanceTo(desired);
        if (distance + 0.10 < s.bestWaypointDistance) {
            s.bestWaypointDistance = distance;
            s.lastProgress = clock;
        }
        // Validate every edge against the *current* world before executing.
        // Mod adapters may reject a disabled/broken elevator or vanished cart.
        NavigationTerrain world = new NavigationTerrain((ServerLevel) avatar.level(), avatar);
        if (!edge.mode().valid(world, edge.from(), edge.to())) {
            edge.mode().cancel(avatar);
            s.rejectedEnds.add(s.route.get(s.route.size() - 1).to());
            s.route = List.of();
            s.detail = "dynamic_obstacle_replanning";
            s.nextPlan = clock + 10;
            return;
        }
        edge.mode().execute(avatar, edge.from(), edge.to());
    }

    private static void cancelActive(CompanionEntity avatar, Session s) {
        if (!s.route.isEmpty() && s.index < s.route.size())
            s.route.get(s.index).mode().cancel(avatar);
    }

    public static void stop(CompanionEntity avatar) {
        if (session != null && session.avatar.equals(avatar.getUUID())) {
            cancelActive(avatar, session);
            session = null;
        }
        if (probe != null && probe.avatar.equals(avatar.getUUID())) probe = null;
        avatar.getNavigation().stop();
        avatar.getMoveControl().setWantedPosition(avatar.getX(), avatar.getY(), avatar.getZ(), 0);
    }

    public static void cleared(UUID avatarId) {
        if (session != null && session.avatar.equals(avatarId)) session = null;
        if (probe != null && probe.avatar.equals(avatarId)) probe = null;
    }

    public static JsonObject status(CompanionEntity avatar) {
        JsonObject obj = new JsonObject();
        Session s = session;
        if (s == null || !s.avatar.equals(avatar.getUUID())) {
            obj.addProperty("mode", "idle");
            obj.addProperty("status", "idle");
            obj.addProperty("remaining_waypoints", 0);
            obj.addProperty("active_transport", "none");
            com.google.gson.JsonArray registered = new com.google.gson.JsonArray();
            for (var mode : TraversalRegistry.modes()) registered.add(mode.id());
            obj.add("transport_modes", registered);
            return obj;
        }
        obj.addProperty("mode", s.mode.name().toLowerCase());
        obj.addProperty("status", s.detail);
        obj.addProperty("remaining_waypoints", Math.max(0, s.route.size() - s.index));
        obj.addProperty("active_transport", s.route.isEmpty() || s.index >= s.route.size()
            ? "none" : s.route.get(s.index).mode().id());
        com.google.gson.JsonArray available = new com.google.gson.JsonArray();
        for (var mode : TraversalRegistry.modes()) available.add(mode.id());
        obj.add("transport_modes", available);
        obj.addProperty("search_failures", s.failedPlans);
        obj.addProperty("completed_segments", s.completedSegments);
        obj.addProperty("expanded_nodes", s.expandedNodes);
        obj.addProperty("target_player", s.targetPlayer == null ? "" : s.targetPlayer.toString());
        return obj;
    }

    private static String failureText(String reason) {
        return switch (reason) {
            case "unloaded_chunks" -> "I can't verify a safe route: some chunks aren't loaded.";
            case "search_budget_exhausted", "path_too_long" ->
                "I couldn't finish checking a route within the current search budget.";
            case "unsafe_start" -> "I don't have safe footing to start moving.";
            case "out_of_search_range" -> "The requested route starts outside my search area.";
            default -> "I couldn't find a safe route in the nearby loaded terrain (" + reason + ").";
        };
    }

    private static void speak(MinecraftServer server, String message) {
        server.getPlayerList().broadcastSystemMessage(
            Component.literal("<MCCP> " + message), false);
    }
}
