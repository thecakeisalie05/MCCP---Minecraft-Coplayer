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
