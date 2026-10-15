# MCCP — Minecraft Coplayer

**A local-first AI companion brain for Gregtech Evolved (Minecraft Forge 1.20.1).**

MCCP is a Windows desktop control center with a *separate, persistent background brain process*, offline-first API, modifiable live settings, local Ollama integration, goal scheduling, and an action approval queue. This alpha does **not yet include the Minecraft entity/client mod** or working voice chat. It deliberately does not claim to play Minecraft until the bridge is built.

## Download

**Windows portable alpha**: [MCCP rolling release](https://github.com/thecakeisalie05/MCCP---Minecraft-Coplayer/releases/tag/alpha-latest)

The workflow automatically builds a single Windows executable on updates to main. If the link does not exist yet, the [Actions page](https://github.com/thecakeisalie05/MCCP---Minecraft-Coplayer/actions) shows whether the initial build finished.

Download MCCP.exe and run it. It launches a localhost-only background daemon. Closing the window does not terminate the daemon. The dashboard offers Start/Reconnect, Pause Autonomy, Resume, Emergency Stop and Stop Brain.

**Upgrading from an earlier alpha:** The brain daemon survives GUI exit. Installing a new executable does not automatically replace a running daemon. v0.1.4 displays the desktop and daemon versions, warns about a mismatch, and offers a **Restart Brain** button that requests a graceful shutdown and relaunch. On much older builds where shutdown is unavailable, close the GUI and terminate only leftover `MCCP.exe` processes in Task Manager before relaunching. Settings and memories are stored separately in `%APPDATA%\\MCCP` and persist. The restart control stops autonomous planning intentionally.

For meaningful AI chat, install [Ollama](https://ollama.com/), run it locally, then pull the model:
~~~powershell
ollama pull qwen3:4b
~~~
Configure another locally available model in the Brain & Model tab if desired. No OpenAI API key is required.

**Qwen3 inference:** The default Qwen3 model uses a thinking channel. MCCP requests `think: false` through Ollama's `/api/chat` endpoint so the output token budget produces an actual visible answer rather than only reasoning. If MCCP reports an empty response, verify you have downloaded the configured model and update Ollama to its latest version. The local model may need time to load on the first request.

## What's implemented (alpha 0.1)

- Standalone desktop dashboard (built-in Tkinter; no extra installation required for Windows executable)
- Dedicated brain daemon that persists when the window closes
- Authenticated HTTP API bound only to 127.0.0.1:8765
- Live enable/disable of perception, memory, autonomy, conversation and voice placeholder
- Emergency stop, safe daemon shutdown and restart from the desktop
- Single scheduled GUI refresh loop (avoids multiplying background polls)
- Autonomy level and model selection
- Local Ollama chat with bounded replies and a grounding prompt
- Goals, observations, event stream, and limited autonomous suggestions
- A permission-gated action proposal, approval, and command-poll queue
- Persistent configuration, editable personality, memory notes and goals across restarts
- Memory browser/editor (add/delete notes)
- Restart-safe goal identifiers
- Simulated Minecraft player-nearby event for API testing
- Automated Python tests, Windows executable build, and packaged-daemon Windows smoke test

## Alpha limitations

- **No Minecraft integration or in-game embodied character yet.** The endpoint is ready for a subsequent client/mod.
- Voice toggle is reserved for future implementation; no transcription/TTS pipeline is shipped.
- Autonomous mode *proposes spoken next steps* from observed world context; no automatic block-breaking, mining, building, or direct control.
- The local model must be installed and Ollama running; otherwise the dashboard reports a model connection error.
- Approving an action enqueues a command, but nothing executes it until a game-side client polls for commands.
- Local REST v1 is experimental; do not expose port 8765 publicly.
- Windows builds are produced by GitHub Actions, not locally cross-compiled from Linux.

## Develop and test

Python 3.12+ recommended. No third-party Python packages are needed to run from source.

~~~bash
python mccp.py
python mccp.py --daemon
python -m unittest discover -s tests -v
~~~

Settings and token are saved under %APPDATA%\MCCP on Windows or ~/.config/mccp on Linux. An alternate data directory can be specified with MCCP_HOME, and the port with MCCP_PORT.

See [API documentation](docs/API.md) for integration details and the action protocol. See [architecture and roadmap](docs/ARCHITECTURE.md) for module boundaries and next steps.

## Safety

MCCP uses an automatically generated local API token, authenticates all private endpoints, and only binds on loopback. Changes that affect world state require a separate enable switch and user review before entering the output queue. The protocol does not yet provide durable delivery or multiplayer identity verification: **use a local test world until the bridge protocol is hardened.**

License: MIT (see LICENSE).

## Local model reasoning display

MCCP v0.1.5 strips Qwen-style `<think>…</think>` segments before displaying model replies and before recording them in chat history. It also handles legacy model outputs where the opening `<think>` tag was omitted. If the model emits no visible answer after filtering, MCCP shows a usable error instead of treating reasoning as an answer. This is a display safeguard, not full access to or control over a model's internal reasoning. Remember to restart the brain after downloading a new executable.


## Embodied GTE companion (early integration alpha)

**Two executables and one Forge 1.20.1 mod** are built from this repository:
- MCCP.exe — existing background brain/control center
- MCCP-Viewer.exe — independent live telemetry and manual control desk
- mccp-embodiment-0.2.0-alpha.jar — Forge client+integrated-server companion

Start MCCP.exe, put the mod JAR in the GTE Minecraft 1.20.1 Forge \`mods\` folder, enter a single-player world, and start MCCP-Viewer.exe. The viewer can issue **Spawn**, **Move To**, **Look At**, **Follow**, **Stop**, **Say**, and **Despawn** through the same approved-command API intended for future Qwen skill calls. The mod reads the MCCP token from \`%APPDATA%\MCCP\api-token\` and polls \`127.0.0.1:8765\`. Commands execute on the Minecraft server thread.

The companion is a **separate humanoid server entity**, not a second online-authenticated \`ServerPlayer\`. It has its own ID, inventory, health, hunger-like stats, position, and navigation. This avoids requiring an additional Minecraft account. It cannot yet do everything mods reserve for genuine \`ServerPlayer\` instances. Later mod integrations will use dedicated adapters (or an authenticated second client if true player semantics are indispensable).

**Camera limitation:** The first image feed is **48×27 semantic raycast PNG**, showing map colors of collision surfaces in the companion's view. It is *not* a Minecraft GPU-rendered screenshot, does not depict textured blocks/entities/particles, and is clearly marked \`frame_kind=semantic_raycast_png\`. The field can later be upgraded to a real client-side render without changing the viewer's API. Nearby blocks/entities in a forward cone are also reported with IDs, namespace/mod, coordinates, orientations, basic data. The cone is not a fully occlusion-tested frustum; limited to 1200 non-air blocks and 128 entities, updated every 2 seconds, to avoid TPS collapse.

For a dedicated server not running on the user's PC, localhost routing needs a **separate authenticated relay**; this release targets integrated single-player first. The optional operator commands \`/mccp spawn\` and \`/mccp remove\` work when cheats/operators are enabled.

See [bridge protocol](docs/GAME_BRIDGE.md). Experimental; test on a backed-up world.

The viewer also includes incremental step controls (forward/back/strafe, turn, jump) separate from target-coordinate navigation. These are server-executed actions, not keyboard injection into the human's client.

### Viewer telemetry recovery (v0.2.1-alpha)

If the companion appears and speaks in Minecraft but MCCP-Viewer shows no position or camera, the Viewer now displays the **actual API error**, detects an outdated background brain on port 8765, and retries failed refreshes. Download **both** updated Windows executables, choose **Restart Brain** in the MCCP dashboard (or stop leftover MCCP.exe processes after closing its windows), and launch the updated viewer. The mod jar may stay installed if it is already spawning and speaking. If the viewer says **BRAIN CONNECTED / Waiting for companion telemetry**, inspect Minecraft's `latest.log` for `MCCP bridge unavailable` and `telemetry capture failed`; the network and camera paths are separately diagnosable.

## GPU/CPU preview controls (v0.2.2 alpha)

The viewer provides independent **renderer**, **resolution** and **FPS** controls:

- CPU (default): semantic per-pixel server raycasting. At most 3 FPS; expensive frames cause adaptive short-term throttling.
- GPU (experimental): a real GLSL 3.30 vertex+fragment program uploaded to your **Minecraft client GPU** at run time. The mod samples a bounded 32×24×32 nearby block-color occupancy volume approximately every 1.5 seconds, uploads it as an OpenGL 3D texture, and draws the companion's view into a small offscreen framebuffer. The GPU pixel output is read back, PNG-encoded on a worker thread and posted to the brain. **This is a voxel visualization, not Minecraft's actual textured scene**, and does not show entities, shaders or particles.
- Resolution options: 48×27, 64×36, 80×45, 96×54, 128×72 pixels.
- FPS: 1–3 in CPU mode, 1–8 in GPU mode. The viewer fetches frames at up to ~8 updates/s. FPS values are *targets*, dependent on Minecraft rendering and the host load.
- Full block/entity telemetry stays on a slower 2-second cadence, while pose/stats are sampled at roughly 5 Hz.

Run **both updated desktop executables and the new Forge JAR**. Preview configuration is stored by the MCCP daemon and read by the server and client modules independently every few seconds; there is no new port. The GPU setting is opt-in. If you see an OpenGL shader failure in `latest.log`, choose CPU mode. GPU rendering requires the Minecraft client (not a headless dedicated server). This implementation has been CI compiled but requires manual validation with GTE, Embeddium, Oculus and the target GPU; offscreen OpenGL state can interact with rendering mods.


## Navigation and follow improvements

The Forge companion now has a terrain-aware bounded A* search and a movement/follow state machine. The Viewer provides **Follow Once**, **Follow Continuously**, **Stop Following** and **Check Route** controls, as well as a live navigation mode/status/remaining-waypoints line. Continuous follow is an actual persistent server-side behavior with arrival hysteresis, progress detection, drift-driven replanning and backoff. The companion sends "I'm coming!" when it starts following and reports bounded navigation failures through Minecraft chat. See [GAME_BRIDGE.md](docs/GAME_BRIDGE.md) for safety bounds, 3D ladder/door/trapdoor handling, and acceptance tests. This is an alpha; AI action execution should remain manually approved.
