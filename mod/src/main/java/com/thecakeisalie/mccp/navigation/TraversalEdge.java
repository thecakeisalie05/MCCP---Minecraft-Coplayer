package com.thecakeisalie.mccp.navigation;

import net.minecraft.core.BlockPos;

/** Directed weighted path edge. Mode-specific physical execution is retained. */
public record TraversalEdge(BlockPos from, BlockPos to, TraversalMode mode, double cost) {
    public TraversalEdge {
        if (from == null || to == null || mode == null
                || !Double.isFinite(cost) || cost <= 0)
            throw new IllegalArgumentException("Invalid traversal edge");
    }
}
