# MCCP Game Bridge — protocol v1

The transport is designed for manual controls today and local Qwen skills later. The first Forge 1.20.1 mod is an experimental single-player integration.

## Architecture and identity

- MCCP.exe: Python brain daemon on http://127.0.0.1:8765.
- MCCP-Viewer.exe: Tk dashboard using the same authenticated brain API.
- mccp-embodiment.jar: Forge mod that creates a separate, visible, server-owned humanoid companion with a 36-slot inventory, health, hunger-like state, UUID, persistent NBT and vanilla navigation.

**Important:** The companion is a player-like PathfinderMob, NOT an authenticated ServerPlayer or FakePlayer. Mods that require an actual ServerPlayer will need an adapter or a separate authenticated Minecraft client. The avatar avoids requiring a second Minecraft license. This distinction is important to the long-term integration plan.

The mod connects to MCCP using the token file at %APPDATA%\MCCP\api-token, or $MCCP_HOME/api-token, and POSTs to localhost only. Server code must execute commands on Minecraft's server thread; network calls are asynchronous.

## Commands

POST /api/v1/actions/propose
~~~json
{"kind":"move_to","arguments":{"x":12,"y":70,"z":44}}
~~~

POST /api/v1/actions/review
~~~json
{"id":123,"approved":true}
~~~

The mod polls /api/v1/commands/poll every five server ticks. Commands supported by this first mod:

| Skill | Arguments | Behavior |
| --- | --- | --- |
| spawn | {} | Spawn avatar beside first online player |
| despawn | {} | Remove avatar |
| move_to | {"x":12,"y":64,"z":24} | Vanilla pathfind, distance max 64 |
| look_at | {"x":12,"y":65,"z":24} | Face target coordinates |
| follow | {"player":"Name"} | Navigate toward player, one shot |
| stop | {} | Stop path navigation |
| move_relative | {"forward":1,"blocks":2} or {"strafe":-1,"blocks":2} | Navigate a bounded 0–3-block step relative to yaw |
| turn_by | {"degrees":30} | Rotate by at most 90 degrees per command |
| jump | {} | Request one jump |
| say | {"text":"Hello"} | Chat with a labeled MCCP speaker (240-char maximum) |
| inspect | {} | No-op; telemetry continues periodically |

Other existing API skill labels (build, interact) are not implemented by the Minecraft side. Approval means queued, NOT executed. The protocol will need delivery receipts, idempotency keys, per-command state and cancellation before autonomous destructive work is enabled.

## Telemetry

The game mod POSTs JSON to /api/v1/game/telemetry about every 40 server ticks. GET /api/v1/game/state returns the latest report for viewer and agent.

~~~json
{
  "protocol":"mccp-game-v1",
  "companion_id":"UUID",
  "dimension":"minecraft:overworld",
  "timestamp_ms":0,
  "pose":{
    "x":0,"y":64,"z":0,"eye_y":65.6,"yaw":0,"pitch":0,
    "head_yaw":0,"heading":"south","bearing_degrees":0,
    "look":{"x":0,"y":0,"z":1},
    "vx":0,"vy":0,"vz":0
  },
  "stats":{"health":20,"max_health":20,"hunger":20,
    "saturation":5,"air":300,"on_ground":true,"navigation_done":true},
  "inventory":[{"slot":0,"item":"minecraft:stone","mod":"minecraft","count":1}],
  "entities":[{"type":"minecraft:player","mod":"minecraft","x":1,"y":64,"z":2,"yaw":30}],
  "blocks":[{"x":2,"y":64,"z":3,"block":"minecraft:furnace",
    "mod":"minecraft","data":"Block{minecraft:furnace}[facing=north]"}],
  "blocks_truncated":false,
  "block_selection":"front_cone_not_occlusion_filtered",
  "frame_kind":"semantic_raycast_png",
  "frame_width":48,"frame_height":27,"frame_png_base64":"..."
}
~~~

### Caveats

- The 48x27 image is a **synthetic semantic raycast PNG** from the companion eye, colored by map colors. It is NOT an actual GPU-rendered screenshot. No textured blocks, particles, entities, GUI or shaders. A later camera backend can emit client_render_png using the same frame field.
- Block data scans a radius-12 front cone, not a full occlusion-tested frustum. Up to 1200 non-air blocks; truncation is flagged.
- Nearby entities include type/mod, position, yaw/pitch, name and health when relevant, capped at 128.
- Inventory is the companion's own 36-slot backpack, not the human player's.
- Block entity metadata does not include unrestricted NBT/inventories. Opt-in GTCEu/AE2 adapters should be added later.
- Requires integrated singleplayer server and client mod for this alpha, on the same PC as the brain. For a remote dedicated server, design a secure relay; do NOT forward the unaudited localhost API onto a public network.
- Two-second heavy-scan cadence should be profiled for TPS.

## Test procedure

1. Run MCCP.exe and keep its daemon active.
2. Place the validated Forge 1.20.1 mccp-embodiment JAR in GTE's mods folder, and start a backed-up single-player world.
3. Launch MCCP-Viewer.exe and explicitly enable world-changing actions.
4. Click Spawn, wait for the companion to appear next to you, and check the telemetry/image feed.
5. Enter XYZ and press Move To or Look At, or use Follow Once; Stop cancels navigation. Alternately operator /mccp spawn and /mccp remove when cheats are enabled.
6. Compare the viewer position, orientation, visible blocks, entity IDs and stats to in-game behavior.
7. Inspect errors and TPS before trying in a production modpack.

The above are acceptance tests to perform, not claims that the mod has already been tested in GTE.

## Preview config, rendering and transport

Authenticated GET/POST `/api/v1/game/preview/config` accepts:

```json
{"backend":"gpu","width":80,"height":45,"fps":4}
```

The accepted backend is `cpu` or `gpu`; widths 48, 64, 80, 96, 128 have matching 16:9 heights; CPU is limited to 3 FPS; GPU is limited to 8 FPS. MCCP persists the selection and validates inputs.

Independent authenticated POST `/api/v1/game/frame` accepts `mccp-frame-v1` with `companion_id`, `frame_kind=gpu_voxel_shader_png`, dimensions and `frame_png_base64`. The brain combines the newest frame and server observation for GET `/api/v1/game/state`. Camera updates no longer require repeating the large block/entity scan.

The optional client renderer uses two GLSL files in `assets/mccp/shaders/`: `voxel.vert` (fullscreen triangle) and `voxel.frag` (volumetric ray marching). OpenGL 3.3 uses a 32×24×32 RGBA8 occupancy texture and an offscreen framebuffer. The result is a simplified color-coded block scene, not a faithful second Minecraft camera; transparent blocks are currently treated as solid. Shader compilation and framebuffer errors log a warning and disable GPU capture briefly; CPU mode remains selectable. GPU readback and Java-side voxel sampling still cost CPU/render time, even when ray evaluation occurs on the GPU. Benchmark before increasing FPS in heavy modpacks.

`frame_kind=semantic_raycast_png` remains the CPU fallback. If GPU is configured but the client cannot render, live pose/stats and block samples are unaffected, while the camera remains pending. The protocol's `frame_kind` differentiates capture providers for future fully textured cameras.


## Deterministic navigation (new navigation alpha)

The companion now has a bounded **terrain-aware A\*** route planner plus a persistent server-side movement state machine. It does not simply call vanilla \`moveTo(player)\` every few ticks.

- \`move_to\`: check a safe route to the target coordinate, execute grid waypoints using physical movement, and announce if no suitable route was found.
- \`check_route\`: **non-moving** check. Arguments \`{"x":10,"y":70,"z":20}\` or \`{"player":"Name"}\`. Reports the result in Minecraft chat.
- \`follow\`: one-time follow. MCCP says "I'm coming!" only when a route is found, reaches within approximately 2.75 blocks (and similar height), says "I'm here!", then ends the task.
- \`follow_continuous\`: persistent follow that holds position within 2.75 blocks and resumes when separated by more than 4.5 blocks. It replans when the target moves significantly, is obstructed, or movement stalls, rather than restarting \`goto\` every tick. Distant targets are pursued via bounded intermediate regions. A failed plan is retried every five seconds; chat failures are throttled.
- \`stop_follow\` or \`stop\`: end active tracking and movement. Both are allowed even when world-changing actions are disabled.

**Safety and scope:** A\* runs on the loaded server world only (at most about 48 horizontal and 16 vertical blocks per search, up to 6,500 expanded nodes). Routes are discrete traversable 1×2 body cells over solid supports, with one-block climbs/jumps and at most two-block drops. Path candidates reject liquids, certain damaging blocks, unloaded chunks, unsupported falls, and impassable walls; closed wooden doors are traversed by opening them as the agent approaches. Ladder sections are identified as vertical transitions and handled by a custom climbing motion policy. Closed trapdoors can provide floor support but are not automatically opened; iron doors, nonstandard modded doors, special portals and elevators require adapters. Minecraft collision, door redstone, dynamic entities, loaded-chunk changes and modded blocks can still invalidate a planned route. **A failed bounded search is not a mathematical proof that no valid route exists in the entire world.** Failures such as \`search_budget_exhausted\` are reported explicitly instead of claiming global impossibility.

The telemetry includes a \`navigation\` object with \`mode\` (\`idle\`, \`move_to\`, \`follow_once\`, \`follow_continuous\`), \`status\`, \`remaining_waypoints\`, and \`search_failures\`. The viewer displays those states without requiring a brain API protocol change. Manual movement overrides automatic follow; cancellation is explicit. Navigation is intentionally non-persistent across game restarts.

### Manual acceptance tests

On a BACKED-UP creative test world, test flat ground, a 90° corridor, a closed wooden door, a ladder between floors, closed/open trapdoors, a two-block drop and a dangerous tall drop. Then test a moving player with follow continuously, target moving behind obstacles, \`stop_follow\`, and a sealed enclosure for \`check_route\`. Check that MCCP reports only bounded failures and never blindly teleports or takes a tall fall. The Java source has been compiled in CI; these exact in-game cases still require testing before autonomous or production use.


## Baritone-informed segmented navigation (2026-10)

MCCP's original bounded planner could **stand motionless at long range**, because it selected an arbitrary point 32 blocks toward the player and required that point to be reached exactly. Failure to reach that artificial coordinate meant no motion, even when reachable terrain led elsewhere.

The current implementation retains MCCP's own server-owned companion, protocol, and collision-aware movement rules, but adopts several **architectural concepts observed directly in Baritone 1.20.1 source**:

- [Baritone \`AStarPathFinder\`](https://github.com/cabaletta/baritone/blob/1.20.1/src/main/java/baritone/pathing/calc/AStarPathFinder.java): maintain best reachable progress and return a useful partial path when the goal isn't reached.
- [Baritone \`AbstractNodeCostSearch\`](https://github.com/cabaletta/baritone/blob/1.20.1/src/main/java/baritone/pathing/calc/AbstractNodeCostSearch.java): distinguish a successful path segment from reaching the actual goal.
- [Baritone \`Moves\`](https://github.com/cabaletta/baritone/blob/1.20.1/src/main/java/baritone/pathing/movement/Moves.java): movement-transition-specific costs, including cardinal/diagonal movement, safe ascents, descents and climbing.
- [Baritone \`PathingBehavior\`](https://github.com/cabaletta/baritone/blob/1.20.1/src/main/java/baritone/behavior/PathingBehavior.java): separate path calculation from execution and continue segment by segment.

**This is independently implemented MCCP code inspired by those concepts, not a verbatim or complete port of Baritone.** Baritone is LGPL-3.0 and remains copyright its respective authors; see its [license](https://github.com/cabaletta/baritone/blob/1.20.1/LICENSE). We did not introduce a direct runtime dependency or copy Baritone source classes into MCCP.

MCCP's planner now targets the **real player/destination directly**, searching the current bounded, loaded region (48 horizontal and 16 vertical blocks). When it can't prove the full route, it may return a verified **segment** ending at a reachable frontier, walk it, and run a new bounded search from the updated actual position. The planner is incremental and executed on the Minecraft server thread with a ceiling of roughly **110 A* expansions and 1.5 ms per active planning tick** (every other server tick). The diagnostic route check has a smaller separate budget. Calls avoid chunk loading.

Additional improvements: safe diagonal steps with corner-clearance checks; explicit collision/hazard checks; partial progress telemetry (\`expanded_nodes\`, \`completed_segments\`, \`remaining_waypoints\`); no-progress detection measured against the next waypoint; rejection of repeatedly stalled path endpoints; target-motion replanning; retry backoff. \`check_route\` reports a **partial route separately from a complete verified route** and runs without blocking the server thread.

Limitations remain: chunk simulation distance can stop the entity from ticking, modded collision shapes and movement adapters need manual tests, plan geometries can become stale, and this does not implement Baritone's complete Minecraft-player movement executor or sophisticated ahead-of-time path splicing. Planning and route execution must be tested independently in-world. A partial route is useful progress, **not** a guarantee that the final target is reachable.

### Recommended regression scenarios

1. From 80–120 blocks away in an open but loaded/simulated area, \`follow_continuous\` should produce \`planning\` → \`traversing_partial_path\` → repeated segments instead of immediate failure.
2. Behind a solid wall with a remote doorway, the route must detour rather than select a blocked geometric checkpoint.
3. On a sealed enclosure, \`check_route\` must not report full success without reaching the goal.
4. Closed wooden doors, stairs, ladders, trapdoors, one- and two-block drops, dangerous high drops and lava should use conservative transitions and never teleport through obstacles.
5. While moving the target, \`follow_continuous\` should replan and then hold near the player; \`stop_follow\` must cancel movement.
6. Benchmark worst-case search on GTCEu-dense terrain; if tick times spike, reduce the node/time budgets or adjust world access.
