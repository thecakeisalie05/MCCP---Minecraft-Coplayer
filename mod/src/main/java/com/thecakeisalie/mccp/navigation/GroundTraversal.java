package com.thecakeisalie.mccp.navigation;

import com.thecakeisalie.mccp.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.function.Consumer;

/** Conservative walking, stepping, jumping, and <=2-block drops. */
public final class GroundTraversal implements TraversalMode {
    @Override public String id() { return "ground"; }

    @Override public void expand(NavigationTerrain terrain, BlockPos from,
                                 Consumer<TraversalEdge> receiver) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos horizontal = from.relative(d);
            for (int dy : new int[]{0, 1, -1, -2}) {
                BlockPos to = horizontal.offset(0, dy, 0);
                if (valid(terrain, from, to))
                    receiver.accept(new TraversalEdge(from, to, this,
                        dy == 0 ? 1.0 : dy > 0 ? 1.9 : 1.5 + (-dy - 1) * 0.4));
            }
        }
        for (int dx : new int[]{-1, 1}) {
            for (int dz : new int[]{-1, 1}) {
                BlockPos to = from.offset(dx, 0, dz);
                if (valid(terrain, from, to))
                    receiver.accept(new TraversalEdge(from, to, this, Math.sqrt(2)));
            }
        }
    }

    @Override public boolean valid(NavigationTerrain terrain, BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int dy = to.getY() - from.getY();
        if (Math.abs(dx) > 1 || Math.abs(dz) > 1 || (dx == 0 && dz == 0)
                || dy > 1 || dy < -2) return false;
        if (dx != 0 && dz != 0 && dy != 0) return false;
        if (!terrain.standable(from) || !terrain.standable(to)) return false;
        if (dy == 1 && !terrain.bodyClear(from.above())) return false;
        if (dy < 0 && !terrain.bodyClear(to.above())) return false;
        if (dx != 0 && dz != 0) {
            // Check both corners so a diagonal does not squeeze through walls.
            return terrain.standable(from.offset(dx, 0, 0))
                && terrain.standable(from.offset(0, 0, dz));
        }
        return true;
    }

    @Override public void execute(CompanionEntity avatar, BlockPos from, BlockPos to) {
        openDoor(avatar, to);
        Vec3 desired = Vec3.atBottomCenterOf(to);
        Vec3 at = avatar.position();
        double h = Math.hypot(desired.x - at.x, desired.z - at.z);
        if (desired.y > at.y + 0.55 && h < 1.6) avatar.getJumpControl().jump();
        avatar.getMoveControl().setWantedPosition(desired.x, desired.y, desired.z, 1.05);
    }

    private static void openDoor(CompanionEntity avatar, BlockPos to) {
        ServerLevel level = (ServerLevel) avatar.level();
        for (BlockPos p : new BlockPos[]{to, to.above()}) {
            if (!level.hasChunkAt(p)) continue;
            BlockState state = level.getBlockState(p);
            if (NavigationTerrain.woodenDoor(state)
                    && !state.getValue(DoorBlock.OPEN)
                    && avatar.position().distanceToSqr(Vec3.atCenterOf(p)) < 9) {
                ((DoorBlock) state.getBlock()).setOpen(avatar, level, state, p, true);
            }
        }
    }
}
