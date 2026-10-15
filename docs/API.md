# MCCP API v1 (alpha)

Base URL: http://127.0.0.1:8765

All /api/v1/* requests require an X-MCCP-Token header whose value is obtained through the desktop application's Connection tab. Only GET /health is public. The daemon binds loopback only. Responses are JSON and return 400 for invalid input, 401 for missing/incorrect token, and 503 for unavailable local-model inference.

## Endpoints

| Method | Path | Description |
| --- | --- | --- |
| GET | /health | Public health / version |
| GET | /api/v1/state | Complete bounded runtime snapshot, config, goals, proposals, events |
| GET | /api/v1/game/state | Latest independent companion pose, stats, inventory, viewport entities/blocks, semantic PNG camera |
| POST | /api/v1/game/telemetry | Accept versioned sensor report from Forge logical server |
| GET | /api/v1/events?after=123 | Recent events with ID above the supplied number |
| POST | /api/v1/config | Update allowed runtime settings, including individual modules |
| POST | /api/v1/control | Pause / resume autonomy, emergency stop, or shut down the daemon |
| POST | /api/v1/observe | Deliver a game observation; refreshes connection heartbeat |
| POST | /api/v1/message | Text chat to the local Ollama model; returns reply |
| POST | /api/v1/memory | Persist a human-edited memory note |
| POST | /api/v1/memory/delete | Remove a note by ID |
| POST | /api/v1/goals | Add a goal |
| POST | /api/v1/goals/status | Set goal status |
| POST | /api/v1/actions/propose | Stage an action for review |
| POST | /api/v1/actions/review | Approve or reject one proposal |
| POST | /api/v1/commands/poll | Dequeue approved commands for an authorized game bridge |

### Observation

~~~json
{"kind":"player_nearby","payload":{"player":"Example","dimension":"minecraft:overworld","position":{"x":0,"y":64,"z":0}}}
~~~

### Config patch

~~~json
{"model":"qwen3:4b","autonomy":40,"modules":{"autonomy":true,"voice":false}}
~~~

Agent personality instructions can be changed live using a personality string in /api/v1/config. Memory notes can be created with {"text":"The base is at x=200"} and deleted with {"id":123}.

Allowed modules: conversation, perception, memory, autonomy, voice (placeholder). Autonomy is an integer from 0 to 100. The local Ollama URL defaults to http://127.0.0.1:11434. World-changing actions additionally require allow_world_actions = true.

### Propose / approve / execute

1. An agent or rule submits /api/v1/actions/propose with an approved action kind and arguments. The daemon returns a proposal ID.
2. The desktop UI displays the pending proposal.
3. Human operator calls /api/v1/actions/review with {"id":42,"approved":true}.
4. A trusted bridge calls /api/v1/commands/poll with {} to consume all approved queued commands. These are not executed by MCCP itself.

Allowed action names for this alpha: say, look_at, move_to, follow, inspect, interact, build, stop. These are *protocol labels*, not implementations. Tool schemas, actor targeting, idempotency, and execution receipts belong to the future mod API version.

### Conversation

POST /api/v1/message: {"text":"What is the LV machine doing?"}

A successful response contains {"reply":"..."}. Ollama must have the configured model pulled and running on the same computer. MCCP does not call a paid cloud model.

## Known limitations for integration developers

- No delivery acknowledgements, retry strategy, authorization roles, or WebSockets yet. Do not treat command polling as guaranteed delivery.
- Event IDs can restart across process restarts. Snapshot/event retrieval is bounded.
- Only one localhost trusted bridge is assumed.
- Minecraft 1.20.1 Forge integration is planned, not present.


### Runtime control semantics

Send {"operation":"pause"} to pause autonomous planning; {"operation":"resume"} to resume it. Send {"operation":"emergency_stop"} to pause and clear unconsumed game commands and reject outstanding proposals. Approving new actions while paused is blocked. Send {"operation":"shutdown"} to save state and stop the background daemon; the GUI can restart it. This cannot undo actions already received and executed in Minecraft.
