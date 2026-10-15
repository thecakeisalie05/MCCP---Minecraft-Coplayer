package com.thecakeisalie.mccp.navigation;

import com.thecakeisalie.mccp.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Unified safety/world-query facade for all movement adapters. Never loads
 * chunks. A mode must reject unknown cells and never bypass these checks.
 */
public final class NavigationTerrain {
    private final ServerLevel level;
    private final CompanionEntity actor;
    private boolean unloaded;
    private boolean unsafe;

    public NavigationTerrain(ServerLevel level) { this(level, null); }
    public NavigationTerrain(ServerLevel level, CompanionEntity actor) {
        this.level = level;
        this.actor = actor;
    }
    /** Nullable for pure route checks without an attached actor. */
    public CompanionEntity actor() { return actor; }
    public ServerLevel level() { return level; }
    public boolean unloaded() { return unloaded; }
    public boolean unsafe() { return unsafe; }

    public boolean loaded(BlockPos at) {
        if (at.getY() <= level.getMinBuildHeight() + 1
                || at.getY() + 2 >= level.getMaxBuildHeight()
                || !level.hasChunkAt(at)) {
            unloaded = true;
            return false;
        }
        return true;
    }

    public boolean climbable(BlockPos at) {
        return loaded(at) && level.getBlockState(at).is(BlockTags.CLIMBABLE);
    }

    public boolean clear(BlockPos at) {
        if (!loaded(at)) return false;
        BlockState state = level.getBlockState(at);
        if (hazard(state) || !level.getFluidState(at).isEmpty()) {
            unsafe = true;
            return false;
        }
        // Ladder collision is a thin wall-mounted shape, not an impassable
        // full-body block. Treat tagged climbables as traversal space.
        return state.getCollisionShape(level, at).isEmpty()
            || state.is(BlockTags.CLIMBABLE) || woodenDoor(state);
    }

    public boolean bodyClear(BlockPos feet) {
        return clear(feet) && clear(feet.above());
    }

    public boolean standable(BlockPos feet) {
        if (!bodyClear(feet) || !loaded(feet.below())) return false;
        BlockPos ground = feet.below();
        BlockState support = level.getBlockState(ground);
        if (hazard(support)) { unsafe = true; return false; }
        if (climbable(feet) || climbable(ground)) return true;
        // An open trapdoor, fence post, or wall section is not a usable floor
        // merely because its collision shape is nonempty.
        return supportedAtCenter(ground);
    }

    /**
     * Footing at the center of a path grid cell. Partial-height collision
     * surfaces (stairs/slabs) are legitimate supports, but tall fences, thin
     * posts, and edge-on open trapdoors are not full-width standing surfaces.
     */
    private boolean supportedAtCenter(BlockPos supportPos) {
        VoxelShape collision = level.getBlockState(supportPos)
            .getCollisionShape(level, supportPos);
        for (AABB b : collision.toAabbs()) {
            if (b.maxY >= 0.45 && b.maxY <= 1.001
                && b.minX <= 0.30 && b.maxX >= 0.70
                && b.minZ <= 0.30 && b.maxZ >= 0.70) return true;
        }
        return false;
    }

    /**
     * Find a nearby VALID grid anchor from the avatar's REAL foot position.
     * blockPosition() alone is incorrect on stairs/slabs: on a half-height
     * stair it can identify the stair block itself as the occupied feet cell.
     *
     * Never teleport/snap the entity. This selects only the planner's origin.
     * Require actual grounding (or climbing) to avoid inventing floor support
     * while the companion is mid-jump/falling.
     */
    public BlockPos startAnchor(CompanionEntity avatar) {
        if (!avatar.onGround() && !climbable(avatar.blockPosition())
                && !climbable(avatar.blockPosition().below()))
            return null;
        BlockPos raw = avatar.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (int dy : new int[]{0, 1, -1}) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos candidate = raw.offset(dx, dy, dz);
                    double horizontal = Math.hypot(
                        candidate.getX() + 0.5 - avatar.getX(),
                        candidate.getZ() + 0.5 - avatar.getZ());
                    // A valid anchor should overlap the robot's immediate
                    // footprint, not be a fictional tile a block away.
                    if (horizontal < 1.0 && Math.abs(candidate.getY() - avatar.getY()) <= 1.15)
                        candidates.add(candidate);
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(p ->
            Math.pow(p.getX() + 0.5 - avatar.getX(), 2)
            + Math.pow(p.getZ() + 0.5 - avatar.getZ(), 2)
            + Math.pow(p.getY() - avatar.getY(), 2) * 0.40));
        for (BlockPos candidate : candidates) {
            if (standable(candidate)) return candidate;
        }
        return null;
    }

    public static boolean woodenDoor(BlockState state) {
        return state.getBlock() instanceof DoorBlock
            && state.is(BlockTags.WOODEN_DOORS);
    }

    public static boolean hazard(BlockState state) {
        return state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK)
            || state.is(Blocks.CACTUS) || state.is(Blocks.FIRE)
            || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CAMPFIRE)
            || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.SWEET_BERRY_BUSH)
            || state.is(Blocks.POWDER_SNOW);
    }
}
