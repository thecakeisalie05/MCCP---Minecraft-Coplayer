# MCCP architecture / development roadmap

## Current process model

MCCP.exe (desktop GUI, Tkinter)
    ↕ local authenticated JSON HTTP
MCCP.exe --daemon (separate persistent background process)
    ↕ Ollama /api/chat (localhost, on demand)
Future GTE Minecraft 1.20.1 Forge mod
    ↕ REST v1 initially, WebSocket/event streaming later

The desktop can close without terminating the brain, and it can reconnect if relaunched. A settings update changes the live brain object without restarting the process. Data is stored as an atomically replaced JSON document; an independent secret is stored in an API token file.

## Modules

- perception: validates and stores recent game observations
- conversation: local LLM requests (Ollama)
- autonomy: bounded interval goal-step suggestions when the game is connected
- memory: persistent note storage foundation (UI/editor not yet provided)
- goals: persisted goals and statuses
- actions: review queue and permission-gated command outbox
- runtime: event stream, config, process lifecycle
- voice: configurable placeholder; not implemented

## Planned iterations

### 0.2 — Harden the foundations
- Split monolithic Python file into engine, transport, and desktop layers
- Persist identity / event IDs, goals, and task checkpoints more robustly
- Add tests for failure recovery, concurrency, and API edge cases
- Integrate Ollama model discovery and hardware/resource diagnostics
- Build GUI controls for all configurable fields, including changing local Ollama URL

### 0.3 — Cognition
- Explicit goal planner with cancel/pause/resume
- Short/long-term semantic memory, editable memory browser, export and reset
- Structured local-model function calling and deterministic action validation
- Agent behavior policies, priorities, rate and token budgets

### 0.4 — Bridge
- Versioned WebSocket transport with reconnect, heartbeats, and receipts
- Entity controller and modded block/machine perception for Forge 1.20.1
- Perception prioritization and event batching to protect TPS
- Position, gaze, nearby player and inventory observation protocols

### 0.5 — Embodiment
- Movement/physics on a client/controller separate from reasoning
- Task-scoped permissions for interactions and block changes
- Spatial references (look direction / proximity), social attention
- Companion's persistent identity/world map

### 0.6 — Speech and independent behavior
- Local speech recognition and synthesized speech
- Voice activity / player-address detection
- Curiosity goals and human interaction policy

## Resource budget

Use the GPU for local inference when available, and run pathfinding / reactive AI outside the LLM to avoid inference calls per tick. Do not run inference on Minecraft's server tick thread. When Ollama is not present, the API and controls still operate.

## Engineering constraints

Release artifacts must be compiled on Windows, not renamed Linux executables. Integration actions require independent authorization, explicit allowlists, and a local game-side implementation. Never expose the API to the network without stronger authentication and transport protection.
