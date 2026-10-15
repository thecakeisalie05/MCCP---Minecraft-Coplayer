package com.thecakeisalie.mccp.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Register a future mod-specific movement mode during setup.
 * Search discovers its directed weighted edges automatically, and route
 * execution dispatches them to the same mode. No planner rewrite required.
 * Restrict custom adapters to trusted code: their execute() runs server-side.
 */
public final class TraversalRegistry {
    private static volatile List<TraversalMode> modes =
        List.of(new GroundTraversal(), new LadderTraversal());

    private TraversalRegistry() {}

    public static List<TraversalMode> modes() { return modes; }

    public static synchronized void register(TraversalMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (mode.id() == null || mode.id().isBlank()) throw new IllegalArgumentException("Missing mode id");
        for (TraversalMode known : modes) {
            if (known.id().equals(mode.id())) throw new IllegalArgumentException("Duplicate mode " + mode.id());
        }
        ArrayList<TraversalMode> updated = new ArrayList<>(modes);
        updated.add(mode);
        modes = List.copyOf(updated);
    }
}
