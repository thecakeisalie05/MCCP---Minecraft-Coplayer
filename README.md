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
