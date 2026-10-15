package com.thecakeisalie.mccp.navigation;

import com.thecakeisalie.mccp.CompanionEntity;
import net.minecraft.core.BlockPos;
import java.util.function.Consumer;

/**
 * Movement plugin. Registered modes supply VALIDATED graph edges and the
 * actual server-thread action that executes those edges.
 *
 * A future elevator / minecart / local teleporter can implement this without
 * replacing A*, following, or the viewer protocol. Moves are restricted to
 * the current loaded dimension in this version.
 */
public interface TraversalMode {
    String id();
    void expand(NavigationTerrain terrain, BlockPos from, Consumer<TraversalEdge> receiver);
    boolean valid(NavigationTerrain terrain, BlockPos from, BlockPos to);
    void execute(CompanionEntity avatar, BlockPos from, BlockPos to);

    default boolean arrived(CompanionEntity avatar, BlockPos to) {
        double x = to.getX() + 0.5 - avatar.getX();
        double z = to.getZ() + 0.5 - avatar.getZ();
        return x * x + z * z < 0.65 * 0.65
            && Math.abs(to.getY() - avatar.getY()) < 0.8;
    }

    /** Future transit modes may allow more time for vehicles or elevators. */
    default int stallTimeoutTicks() { return 68; }

    default void cancel(CompanionEntity avatar) {
        avatar.getNavigation().stop();
    }
}
