package com.thecakeisalie.mccp.navigation;

import com.thecakeisalie.mccp.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.function.Consumer;

/**
 * Up/down climbable-column edges (including ladders and compatible blocks).
 * Uses controlled velocity rather than teleportation.
 * Actual top/bottom mounting and modded climbs require in-world validation.
 */
public final class LadderTraversal implements TraversalMode {
    @Override public String id() { return "climbable"; }

    @Override public void expand(NavigationTerrain terrain, BlockPos from,
                                 Consumer<TraversalEdge> receiver) {
        for (int dy : new int[]{-1, 1}) {
            BlockPos to = from.offset(0, dy, 0);
            if (valid(terrain, from, to))
                receiver.accept(new TraversalEdge(from, to, this, 1.6));
        }
    }

    @Override public boolean valid(NavigationTerrain terrain, BlockPos from, BlockPos to) {
        if (from.getX() != to.getX() || from.getZ() != to.getZ()
                || Math.abs(to.getY() - from.getY()) != 1) return false;
        if (!terrain.standable(from) || !terrain.standable(to)) return false;
        // Never climb arbitrary air. An adjacent climbable block must support
        // this vertical move; the extra node allows dismounting at the top.
        return terrain.climbable(from) || terrain.climbable(to);
    }

    @Override public void execute(CompanionEntity avatar, BlockPos from, BlockPos to) {
        Vec3 at = avatar.position();
        Vec3 goal = Vec3.atBottomCenterOf(to);
        double dy = goal.y - at.y;
        avatar.setDeltaMovement(
            Math.max(-0.12, Math.min(0.12, (goal.x - at.x) * 0.25)),
            dy > 0.10 ? 0.20 : dy < -0.10 ? -0.15 : 0,
            Math.max(-0.12, Math.min(0.12, (goal.z - at.z) * 0.25)));
        avatar.hasImpulse = true;
    }
}
