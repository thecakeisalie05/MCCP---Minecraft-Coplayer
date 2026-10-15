package com.thecakeisalie.mccp;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Server-tick-owned navigation state machine. Goals survive movement
 * interruptions but do not survive server restart; players reissue follow.
 *
 * Follow-once completes on first arrival. Continuous follow uses distance
 * hysteresis, destination-drift replanning, progress monitoring, and retry
 * backoff. It does NOT restart the entire path every tick.
 */
public final class SmartNavigation {
    private static final double ARRIVE = 2.75;
    private static final double RESUME = 4.5;
    private static final int REPATH_INTERVAL = 24;
    private static final int RETRY_INTERVAL = 100;
    private static final int STUCK_INTERVAL = 70;

    private static Session session;
    private static long clock;

    public enum Mode { IDLE, MOVE_TO, FOLLOW_ONCE, FOLLOW_CONTINUOUS }

    private static final class Session {
        final UUID avatar;
        Mode mode;
        UUID targetPlayer;
        Vec3 destination;
        List<BlockPos> route = List.of();
        int index;
        long nextPlan;
        long lastProgress;
        Vec3 lastPosition;
        int failedPlans;
        String detail = "idle";
        boolean holding;
        boolean announcedFailure;

        Session(CompanionEntity avatar, Mode mode) {
            this.avatar = avatar.getUUID();
            this.mode = mode;
            this.lastPosition = avatar.position();
            this.lastProgress = clock;
        }
    }

    private SmartNavigation() {}

    public static void tick(MinecraftServer server) {
        clock++;
        Session s = session;
        if (s == null || s.mode == Mode.IDLE) return;
        if (clock % 2 != 0) return;
        CompanionEntity avatar = locate(server, s.avatar);
        if (avatar == null) {
            session = null;
            return;
        }
        ServerPlayer player = null;
        if (s.targetPlayer != null) {
            player = server.getPlayerList().getPlayer(s.targetPlayer);
            if (player == null || player.level() != avatar.level()) {
                if (!s.announcedFailure) speak(server, "I can't follow across dimensions or while you're offline.");
                s.announcedFailure = true;
                s.detail = "target_unavailable";
                if (s.mode != Mode.FOLLOW_CONTINUOUS) stop(avatar);
                return;
            }
        }
        Vec3 target = player == null ? s.destination : player.position();
        double distance = avatar.position().distanceTo(target);
        boolean following = s.mode == Mode.FOLLOW_ONCE || s.mode == Mode.FOLLOW_CONTINUOUS;
        if (distance <= (following ? ARRIVE : 0.95)) {
            if (s.mode == Mode.FOLLOW_CONTINUOUS) {
                s.holding = true;
                s.route = List.of();
                s.detail = "holding_near_player";
                avatar.getNavigation().stop();
                return;
            }
            if (s.mode == Mode.FOLLOW_ONCE) speak(server, "I'm here!");
            stop(avatar);
            return;
        }
        if (s.holding && distance < RESUME) return;
        s.holding = false;

        if (s.lastPosition.distanceToSqr(avatar.position()) > 0.12 * 0.12) {
            s.lastPosition = avatar.position();
            s.lastProgress = clock;
        }
        boolean staleRoute = s.route.isEmpty() || s.index >= s.route.size();
        boolean movedTarget = s.destination == null || (
            following && s.destination.distanceToSqr(target) > 3.0 * 3.0);
        boolean stuck = clock - s.lastProgress > STUCK_INTERVAL;
        if ((staleRoute || movedTarget || stuck) && clock >= s.nextPlan) {
            // Replan on a bounded timer, not continuously and not on every
            // movement. Avoid chasing a target's precise occupied block.
            replan(server, avatar, s, target, following);
            if (s.route.isEmpty()) return;
        }

        if (!s.route.isEmpty()) advance(avatar, s);
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
        Session next = new Session(avatar, continuous ? Mode.FOLLOW_CONTINUOUS : Mode.FOLLOW_ONCE);
        next.targetPlayer = player.getUUID();
        avatar.getNavigation().stop();
        session = next;
        // When already close, a continuous follower waits and begins again
        // only once the player leaves the proximity radius.
        if (avatar.position().distanceTo(player.position()) <= ARRIVE) {
            if (continuous) {
                next.holding = true;
                next.detail = "holding_near_player";
                speak(server, "I'll stick with you!");
            } else {
                speak(server, "I'm already here!");
                stop(avatar);
            }
            return "near_player";
        }
        replan(server, avatar, next, player.position(), true);
        if (next.route.isEmpty()) {
            if (!continuous) stop(avatar);
            return "no_route:" + next.detail;
        }
        speak(server, continuous ? "I'm coming! I'll keep following you." : "I'm coming!");
        return continuous ? "following_continuously" : "following_once";
    }

    public static String moveTo(MinecraftServer server, CompanionEntity avatar, Vec3 target) {
        if (avatar.position().distanceToSqr(target) > 48 * 48)
            return "destination_outside_48_block_search_radius";
        Session next = new Session(avatar, Mode.MOVE_TO);
        next.destination = target;
        avatar.getNavigation().stop();
        session = next;
        replan(server, avatar, next, target, false);
        if (next.route.isEmpty()) {
            stop(avatar);
            return "no_route:" + next.detail;
        }
        return "route_started";
    }

    public static String probe(MinecraftServer server, CompanionEntity avatar, Vec3 target,
                               boolean playerTarget) {
        RoutePlanner.Plan plan = new RoutePlanner(
            (ServerLevel) avatar.level(), avatar.blockPosition(), target, playerTarget).solve();
        if (plan.found()) {
            speak(server, "I found a safe route in the loaded area (" +
                (plan.nodes().size() - 1) + " steps).");
            return "route_found:" + plan.nodes().size();
        }
        speak(server, routeFailure(plan.reason()));
        return "no_route:" + plan.reason();
    }

    private static void replan(MinecraftServer server, CompanionEntity avatar,
                               Session s, Vec3 target, boolean playerTarget) {
        Vec3 routeGoal = target;
        if (playerTarget && avatar.position().distanceToSqr(target) > 38 * 38) {
            // A bounded local segment avoids trying to search the entire world
            // when following a player further than the loaded 3D neighborhood.
            Vec3 delta = target.subtract(avatar.position());
            Vec3 horizontal = new Vec3(delta.x, 0, delta.z);
            if (horizontal.lengthSqr() > 1) {
                Vec3 step = horizontal.normalize().scale(32);
                double y = avatar.getY() + Math.max(-10, Math.min(10, delta.y));
                routeGoal = new Vec3(avatar.getX() + step.x, y, avatar.getZ() + step.z);
            }
        }
        RoutePlanner.Plan plan = new RoutePlanner(
            (ServerLevel) avatar.level(), avatar.blockPosition(), routeGoal, playerTarget).solve();
        s.nextPlan = clock + (plan.found() ? REPATH_INTERVAL : RETRY_INTERVAL);
        s.detail = plan.reason();
        s.destination = target;
        if (!plan.found()) {
            s.failedPlans++;
            s.route = List.of();
            if (!s.announcedFailure) speak(server, routeFailure(plan.reason()) +
                (s.mode == Mode.FOLLOW_CONTINUOUS ? " I'll try again if things change." : ""));
            s.announcedFailure = true;
            if (s.mode == Mode.FOLLOW_ONCE || s.mode == Mode.MOVE_TO) stop(avatar);
            return;
        }
        if (s.announcedFailure) speak(server, "I found another way. I'm coming!");
        s.announcedFailure = false;
        s.route = plan.nodes();
        s.index = Math.min(1, s.route.size());
        s.failedPlans = 0;
        s.lastPosition = avatar.position();
        s.lastProgress = clock;
        s.detail = "navigating";
    }

    private static void advance(CompanionEntity avatar, Session s) {
        if (s.index >= s.route.size()) {
            s.route = List.of();
            return;
        }
        BlockPos next = s.route.get(s.index);
        Vec3 desired = Vec3.atBottomCenterOf(next);
        Vec3 current = avatar.position();
        double horizontal = Math.hypot(desired.x - current.x, desired.z - current.z);
        if (horizontal < 0.65 && Math.abs(desired.y - current.y) < 0.75) {
            s.index++;
            s.lastProgress = clock;
            if (s.index >= s.route.size()) return;
            next = s.route.get(s.index);
            desired = Vec3.atBottomCenterOf(next);
            horizontal = Math.hypot(desired.x - current.x, desired.z - current.z);
        }
        if (!canStillPass(avatar, next)) {
            s.route = List.of();
            s.nextPlan = clock + REPATH_INTERVAL;
            s.detail = "dynamic_obstacle";
            return;
        }
        openNearbyDoors(avatar, next);
        BlockPos currentCell = avatar.blockPosition();
        boolean climbing = avatar.level().getBlockState(currentCell).is(BlockTags.CLIMBABLE)
            || avatar.level().getBlockState(next).is(BlockTags.CLIMBABLE);
        if (climbing && desired.y > current.y + 0.15) {
            // Vanilla ground navigation cannot climb ladders reliably;
            // drive the approved vertical edge using living-entity physics.
            Vec3 vec = avatar.getDeltaMovement();
            avatar.setDeltaMovement(
                Math.max(-0.12, Math.min(0.12, (desired.x - current.x) * 0.25)),
                0.20,
                Math.max(-0.12, Math.min(0.12, (desired.z - current.z) * 0.25)));
            avatar.hasImpulse = true;
        } else {
            if (desired.y > current.y + 0.55 && horizontal < 1.6)
                avatar.getJumpControl().jump();
            avatar.getMoveControl().setWantedPosition(desired.x, desired.y, desired.z, 1.05);
        }
        // There is no blind teleportation: every motion comes through the
        // entity's move controls and collision system.
    }

    private static boolean canStillPass(CompanionEntity avatar, BlockPos pos) {
        ServerLevel level = (ServerLevel) avatar.level();
        if (!level.hasChunkAt(pos) || !level.hasChunkAt(pos.above())) return false;
        for (BlockPos probe : new BlockPos[]{pos, pos.above()}) {
            BlockState state = level.getBlockState(probe);
            if (!state.getCollisionShape(level, probe).isEmpty()
                && !RoutePlanner.isOpenableDoor(state)) return false;
        }
        return true;
    }

    private static void openNearbyDoors(CompanionEntity avatar, BlockPos step) {
        ServerLevel level = (ServerLevel) avatar.level();
        for (BlockPos at : new BlockPos[]{step, step.above()}) {
            BlockState state = level.getBlockState(at);
            if (RoutePlanner.isOpenableDoor(state) &&
                avatar.position().distanceToSqr(Vec3.atCenterOf(at)) < 9.0 &&
                !state.getValue(DoorBlock.OPEN)) {
                ((DoorBlock) state.getBlock()).setOpen(avatar, level, state, at, true);
            }
        }
    }

    public static void stop(CompanionEntity avatar) {
        if (session != null && session.avatar.equals(avatar.getUUID())) session = null;
        avatar.getNavigation().stop();
        avatar.getMoveControl().setWantedPosition(avatar.getX(), avatar.getY(), avatar.getZ(), 0);
    }

    public static void cleared(UUID avatarId) {
        if (session != null && session.avatar.equals(avatarId)) session = null;
    }

    public static JsonObject status(CompanionEntity avatar) {
        JsonObject obj = new JsonObject();
        Session s = session;
        if (s == null || !s.avatar.equals(avatar.getUUID())) {
            obj.addProperty("mode", "idle");
            obj.addProperty("status", "idle");
            obj.addProperty("remaining_waypoints", 0);
            return obj;
        }
        obj.addProperty("mode", s.mode.name().toLowerCase());
        obj.addProperty("status", s.detail);
        obj.addProperty("remaining_waypoints", Math.max(0, s.route.size() - s.index));
        obj.addProperty("search_failures", s.failedPlans);
        obj.addProperty("target_player", s.targetPlayer == null ? "" : s.targetPlayer.toString());
        return obj;
    }

    private static String routeFailure(String reason) {
        return switch (reason) {
            case "unloaded_chunks" -> "I can't verify a safe route: part of the area isn't loaded.";
            case "search_budget_exhausted", "path_too_long" ->
                "I couldn't finish checking the route within my search limits.";
            case "unsafe_start" -> "I don't have safe footing to start moving.";
            case "out_of_search_range" -> "That destination is outside my navigation range.";
            default -> "I couldn't find a safe route through the nearby loaded terrain (" +
                reason + ").";
        };
    }

    private static void speak(MinecraftServer server, String message) {
        server.getPlayerList().broadcastSystemMessage(
            Component.literal("<MCCP> " + message), false);
    }
}
