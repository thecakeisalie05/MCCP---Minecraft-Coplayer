"""MCCP local-first companion brain and desktop control center.

No cloud API credentials required. Python stdlib runtime; bundled into one Windows exe.
"""
from __future__ import annotations

import json
import os
import secrets
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

API_VERSION = "v1"
PORT = int(os.environ.get("MCCP_PORT", "8765"))
HOST = "127.0.0.1"


def data_directory() -> Path:
    custom = os.environ.get("MCCP_HOME")
    if custom:
        return Path(custom).expanduser()
    if os.name == "nt":
        return Path(os.environ.get("APPDATA", str(Path.home()))) / "MCCP"
    return Path.home() / ".config" / "mccp"


DATA = data_directory()
CONFIG_FILE = DATA / "brain.json"
TOKEN_FILE = DATA / "api-token"
DEFAULT_CONFIG = {
    "model": "qwen3:4b",
    "ollama_url": "http://127.0.0.1:11434",
    "autonomy": 50,
    "autonomy_interval": 60,
    "modules": {
        "conversation": True, "perception": True, "memory": True,
        "autonomy": True, "voice": False
    },
    "allow_world_actions": False,
}
ALLOWED_ACTIONS = {
    "say", "look_at", "move_to", "follow", "inspect",
    "interact", "build", "stop"
}


def atomic_json_write(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False), encoding="utf-8")
    os.replace(temporary, path)


def get_or_create_token() -> str:
    DATA.mkdir(parents=True, exist_ok=True)
    if not TOKEN_FILE.exists():
        TOKEN_FILE.write_text(secrets.token_urlsafe(32), encoding="utf-8")
        if os.name != "nt":
            TOKEN_FILE.chmod(0o600)
    return TOKEN_FILE.read_text(encoding="utf-8").strip()


class Brain:
    def __init__(self) -> None:
        self.lock = threading.RLock()
        self.config = json.loads(json.dumps(DEFAULT_CONFIG))
        self.goals: list[dict] = []
        self.memory: list[dict] = []
        self.events: deque[dict] = deque(maxlen=400)
        self.observations: deque[dict] = deque(maxlen=80)
        self.proposals: list[dict] = []
        self.outbox: list[dict] = []
        self.messages: deque[dict] = deque(maxlen=40)
        self.paused = False
        self.last_seen = 0.0
        self.counter = 0
        self.next_think = 0.0
        self.load()
        self.event("runtime", "Brain process initialized")

    def load(self) -> None:
        if not CONFIG_FILE.exists():
            return
        try:
            saved = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
            config = saved.get("config", {})
            self.config.update({k: v for k, v in config.items() if k in DEFAULT_CONFIG and k != "modules"})
            if isinstance(config.get("modules"), dict):
                self.config["modules"].update({
                    k: bool(v) for k, v in config["modules"].items()
                    if k in DEFAULT_CONFIG["modules"]
                })
            self.goals = saved.get("goals", [])[:100]
            self.memory = saved.get("memory", [])[-150:]
        except (OSError, ValueError, TypeError) as exc:
            print("Could not load MCCP saved state:", exc, file=sys.stderr)

    def save(self) -> None:
        with self.lock:
            atomic_json_write(CONFIG_FILE, {
                "version": 1, "config": self.config, "goals": self.goals,
                "memory": self.memory[-150:]
            })

    def _id(self) -> int:
        self.counter += 1
        return self.counter

    def event(self, kind: str, description: str, **details) -> dict:
        with self.lock:
            entry = {
                "id": self._id(), "time": round(time.time(), 3),
                "kind": kind, "text": str(description)[:1000], **details
            }
            self.events.append(entry)
            return entry

    def snapshot(self) -> dict:
        with self.lock:
            last_seen_seconds = round(time.time() - self.last_seen, 1) if self.last_seen else None
            return {
                "api_version": API_VERSION, "running": True, "paused": self.paused,
                "connected": bool(self.last_seen and time.time() - self.last_seen < 15),
                "last_seen_seconds": last_seen_seconds,
                "config": json.loads(json.dumps(self.config)),
                "goals": list(self.goals),
                "proposals": list(self.proposals[-50:]),
                "memory_count": len(self.memory),
                "observation_count": len(self.observations),
                "recent_observations": list(self.observations)[-5:],
                "events": list(self.events)[-75:],
                "messages": list(self.messages)[-20:],
                "queued_actions": len(self.outbox)
            }

    def update_config(self, updates: dict) -> dict:
        if not isinstance(updates, dict):
            raise ValueError("Settings must be an object")
        with self.lock:
            for key, value in updates.items():
                if key == "modules":
                    if not isinstance(value, dict):
                        raise ValueError("modules must be an object")
                    for name, enabled in value.items():
                        if name not in DEFAULT_CONFIG["modules"] or not isinstance(enabled, bool):
                            raise ValueError("Unknown module or non-boolean toggle: " + str(name))
                        self.config["modules"][name] = enabled
                elif key == "autonomy":
                    if type(value) not in (int, float) or not 0 <= value <= 100:
                        raise ValueError("autonomy must be between 0 and 100")
                    self.config[key] = int(value)
                elif key == "autonomy_interval":
                    if type(value) is not int or not 15 <= value <= 3600:
                        raise ValueError("autonomy_interval must be between 15 and 3600 seconds")
                    self.config[key] = value
                elif key == "model":
                    if not isinstance(value, str) or not 1 <= len(value) <= 120:
                        raise ValueError("Invalid model name")
                    self.config[key] = value.strip()
                elif key == "ollama_url":
                    parsed = urllib.parse.urlsplit(str(value))
                    if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost"):
                        raise ValueError("Only a local Ollama URL is permitted in this alpha")
                    self.config[key] = str(value).rstrip("/")
                elif key == "allow_world_actions":
                    if not isinstance(value, bool):
                        raise ValueError("allow_world_actions must be boolean")
                    self.config[key] = value
                else:
                    raise ValueError("Unknown setting: " + str(key))
            self.save()
            self.event("settings", "Runtime configuration updated", changed=list(updates))
            return self.snapshot()

    def add_goal(self, text: str) -> dict:
        if not isinstance(text, str) or not text.strip() or len(text) > 500:
            raise ValueError("Goal must contain 1–500 characters")
        with self.lock:
            goal = {"id": self._id(), "text": text.strip(), "status": "pending", "created": time.time()}
            self.goals.append(goal)
            self.save()
            self.event("goal", "New goal: " + goal["text"])
            return goal

    def set_goal(self, goal_id: int, status: str) -> dict:
        if status not in ("pending", "active", "done", "cancelled"):
            raise ValueError("Unknown goal status")
        with self.lock:
            for goal in self.goals:
                if goal["id"] == goal_id:
                    goal["status"] = status
                    self.save()
                    self.event("goal", "Goal " + str(goal_id) + ": " + status)
                    return goal
        raise ValueError("Goal not found")

    def observe(self, observation: dict) -> dict:
        if not isinstance(observation, dict) or not isinstance(observation.get("kind"), str):
            raise ValueError("Observation must have a kind")
        if len(json.dumps(observation)) > 16000:
            raise ValueError("Observation too large")
        with self.lock:
            self.last_seen = time.time()
            if self.config["modules"]["perception"]:
                item = {
                    "id": self._id(), "time": self.last_seen,
                    "kind": observation["kind"][:80],
                    "payload": observation.get("payload", {})
                }
                self.observations.append(item)
                self.event("perception", "Received " + item["kind"])
                return item
            return {"received": True, "ignored": "perception disabled"}

    def propose(self, kind: str, arguments: dict) -> dict:
        if kind not in ALLOWED_ACTIONS or not isinstance(arguments, dict):
            raise ValueError("Unsupported action or arguments")
        if len(json.dumps(arguments)) > 8000:
            raise ValueError("Action arguments too large")
        with self.lock:
            proposal = {"id": self._id(), "kind": kind, "arguments": arguments,
                        "status": "pending", "time": time.time()}
            self.proposals.append(proposal)
            self.proposals = self.proposals[-100:]
            self.event("action", "Proposed: " + kind)
            return proposal

    def decide_proposal(self, proposal_id: int, approved: bool) -> dict:
        with self.lock:
            for proposal in self.proposals:
                if proposal["id"] != proposal_id:
                    continue
                if proposal["status"] != "pending":
                    raise ValueError("Proposal already reviewed")
                if approved and proposal["kind"] not in ("say", "inspect", "look_at", "stop") \
                        and not self.config["allow_world_actions"]:
                    raise ValueError("World actions are disabled; enable them first")
                proposal["status"] = "approved" if approved else "rejected"
                if approved:
                    self.outbox.append({
                        "id": proposal["id"], "kind": proposal["kind"],
                        "arguments": proposal["arguments"]
                    })
                self.event("action", proposal["status"] + ": " + proposal["kind"])
                return proposal
        raise ValueError("Proposal not found")

    def poll_commands(self) -> list[dict]:
        with self.lock:
            outgoing = self.outbox[:]
            self.outbox.clear()
            return outgoing

    def ollama(self, prompt: str) -> str:
        if not self.config["modules"]["conversation"]:
            raise RuntimeError("Conversation module is disabled")
        with self.lock:
            context = list(self.observations)[-4:]
            memories = self.memory[-8:] if self.config["modules"]["memory"] else []
            model = self.config["model"]
            url = self.config["ollama_url"]
        system = (
            "You are MCCP, a friendly Minecraft coplayer with your own initiative. "
            "You inhabit a modded Minecraft world; never claim you performed an action unless "
            "a game tool confirms it. Prefer concise, grounded responses. "
            "You cannot directly manipulate Minecraft through this text channel. "
            "Latest world observations: " + json.dumps(context, ensure_ascii=False)[-3000:] +
            "\nSelected long-term notes: " + json.dumps(memories, ensure_ascii=False)[-1000:]
        )
        payload = json.dumps({
            "model": model, "stream": False,
            "messages": [{"role": "system", "content": system},
                         {"role": "user", "content": prompt}],
            "options": {"num_predict": 240}
        }).encode("utf-8")
        request = urllib.request.Request(
            url + "/api/chat", data=payload,
            headers={"Content-Type": "application/json"}, method="POST"
        )
        try:
            with urllib.request.urlopen(request, timeout=50) as response:
                result = json.load(response)
        except (urllib.error.URLError, TimeoutError, ValueError) as exc:
            raise RuntimeError("Ollama request failed. Is Ollama running with model " + model + "? " + str(exc))
        reply = result.get("message", {}).get("content", "")
        if not isinstance(reply, str) or not reply.strip():
            raise RuntimeError("The model returned no text")
        with self.lock:
            self.messages.append({"role": "user", "text": prompt[:1000]})
            self.messages.append({"role": "assistant", "text": reply[:6000]})
            self.event("conversation", "Answered a message")
        return reply.strip()

    def autonomous_tick(self) -> None:
        with self.lock:
            if self.paused or not self.config["modules"]["autonomy"] or not self.config["autonomy"]:
                return
            if not self.last_seen or time.time() - self.last_seen > 30:
                return
            if time.time() < self.next_think:
                return
            goal = next((g for g in self.goals if g["status"] in ("active", "pending")), None)
            if goal is None:
                return
            if goal["status"] == "pending":
                goal["status"] = "active"
                self.save()
            self.next_think = time.time() + self.config["autonomy_interval"]
            summary = str(goal["text"])
        try:
            thought = self.ollama(
                "You have a goal: " + summary +
                "\nBased only on observations available, suggest one safe, specific next step "
                "to the human player in one sentence. Do not pretend you executed it."
            )
            self.propose("say", {"text": thought[:400]})
            self.event("autonomy", "Suggested next step for goal: " + summary)
        except RuntimeError as exc:
            self.event("error", str(exc)[:400])

    def autonomy_loop(self) -> None:
        while True:
            try:
                self.autonomous_tick()
            except Exception as exc:
                self.event("error", "Autonomy loop: " + str(exc)[:300])
            time.sleep(2)


class Handler(BaseHTTPRequestHandler):
    server_version = "MCCP/0.1"
    brain: Brain
    token: str

    def log_message(self, format, *args):
        return  # Quiet local daemon; state and errors are available in the dashboard.

    def json_response(self, code: int, value) -> None:
        raw = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def authorized(self) -> bool:
        if secrets.compare_digest(self.headers.get("X-MCCP-Token", ""), self.token):
            return True
        self.json_response(401, {"error": "Missing or incorrect X-MCCP-Token"})
        return False

    def read_body(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length < 1 or length > 65536:
            raise ValueError("Body must be 1–65536 bytes")
        return json.loads(self.rfile.read(length))

    def do_GET(self):
        path = urllib.parse.urlsplit(self.path).path
        if path == "/health":
            return self.json_response(200, {"ok": True, "service": "mccp-brain", "version": "0.1.0"})
        if not self.authorized():
            return
        if path == "/api/v1/state":
            return self.json_response(200, self.brain.snapshot())
        if path == "/api/v1/events":
            query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
            try:
                after = int(query.get("after", ["0"])[0])
            except ValueError:
                after = 0
            return self.json_response(200, {"events": [
                e for e in self.brain.snapshot()["events"] if e["id"] > after
            ]})
        return self.json_response(404, {"error": "Unknown endpoint"})

    def do_POST(self):
        if not self.authorized():
            return
        try:
            data = self.read_body()
            path = urllib.parse.urlsplit(self.path).path
            if not isinstance(data, dict):
                raise ValueError("Expected JSON object")
            if path == "/api/v1/config":
                result = self.brain.update_config(data)
            elif path == "/api/v1/observe":
                result = self.brain.observe(data)
            elif path == "/api/v1/goals":
                result = self.brain.add_goal(data.get("text", ""))
            elif path == "/api/v1/goals/status":
                result = self.brain.set_goal(int(data.get("id", -1)), data.get("status", ""))
            elif path == "/api/v1/message":
                result = {"reply": self.brain.ollama(str(data.get("text", ""))[:2000])}
            elif path == "/api/v1/actions/propose":
                result = self.brain.propose(data.get("kind"), data.get("arguments", {}))
            elif path == "/api/v1/actions/review":
                result = self.brain.decide_proposal(int(data.get("id", -1)), data.get("approved") is True)
            elif path == "/api/v1/commands/poll":
                result = {"commands": self.brain.poll_commands()}
            elif path == "/api/v1/control":
                operation = data.get("operation")
                if operation not in ("pause", "resume"):
                    raise ValueError("Control operation must be pause or resume")
                self.brain.paused = operation == "pause"
                self.brain.event("runtime", "Runtime " + ("paused" if self.brain.paused else "resumed"))
                result = {"paused": self.brain.paused}
            else:
                return self.json_response(404, {"error": "Unknown endpoint"})
            return self.json_response(200, result)
        except (ValueError, TypeError) as exc:
            self.json_response(400, {"error": str(exc)})
        except RuntimeError as exc:
            self.json_response(503, {"error": str(exc)})
        except Exception as exc:
            self.brain.event("error", str(exc)[:300])
            self.json_response(500, {"error": "Internal daemon error"})


def serve() -> None:
    token = get_or_create_token()
    brain = Brain()
    class BoundHandler(Handler):
        pass
    BoundHandler.brain = brain
    BoundHandler.token = token
    server = ThreadingHTTPServer((HOST, PORT), BoundHandler)
    threading.Thread(target=brain.autonomy_loop, daemon=True).start()
    print("MCCP brain listening at http://" + HOST + ":" + str(PORT), flush=True)
    try:
        server.serve_forever(poll_interval=0.5)
    finally:
        server.server_close()


def api(method: str, path: str, data=None, timeout: float = 2.0):
    payload = None if data is None else json.dumps(data).encode("utf-8")
    token = get_or_create_token()
    request = urllib.request.Request(
        "http://" + HOST + ":" + str(PORT) + path,
        data=payload, method=method, headers={
            "X-MCCP-Token": token, "Content-Type": "application/json"
        }
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        try:
            problem = json.load(exc)
            raise RuntimeError(problem.get("error", str(exc)))
        except ValueError:
            raise RuntimeError(str(exc))


def daemon_command() -> list[str]:
    if getattr(sys, "frozen", False):
        return [sys.executable, "--daemon"]
    return [sys.executable, str(Path(__file__).resolve()), "--daemon"]


def start_daemon() -> None:
    try:
        api("GET", "/health", timeout=0.3)
        return
    except Exception:
        pass
    kwargs = {"stdin": subprocess.DEVNULL, "stdout": subprocess.DEVNULL,
              "stderr": subprocess.DEVNULL}
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.DETACHED_PROCESS
    else:
        kwargs["start_new_session"] = True
    subprocess.Popen(daemon_command(), **kwargs)


def launch_gui() -> None:
    import tkinter as tk
    from tkinter import messagebox, ttk

    start_daemon()
    root = tk.Tk()
    root.title("MCCP | Minecraft Coplayer Brain")
    root.geometry("1110x760")
    root.minsize(880, 620)
    root.configure(bg="#101820")
    style = ttk.Style(root)
    style.theme_use("clam")
    style.configure(".", background="#101820", foreground="#e9f1f5", font=("Segoe UI", 10))
    style.configure("TFrame", background="#101820")
    style.configure("TLabel", background="#101820", foreground="#e9f1f5")
    style.configure("TButton", padding=7, background="#273b4c", foreground="#f4f7fa")
    style.map("TButton", background=[("active", "#395976")])
    style.configure("TCheckbutton", background="#101820", foreground="#e9f1f5")
    style.configure("TNotebook", background="#101820", borderwidth=0)
    style.configure("TNotebook.Tab", background="#23313d", foreground="#d6e5ec", padding=(15, 9))
    style.map("TNotebook.Tab", background=[("selected", "#375d79")])
    style.configure("TEntry", fieldbackground="#1c2a35", foreground="#eef4f7")
    style.configure("TScale", background="#101820")
    style.configure("TLabelframe", background="#101820", foreground="#f4f7fa")
    style.configure("TLabelframe.Label", background="#101820", foreground="#f4f7fa")

    header = ttk.Frame(root, padding=15)
    header.pack(fill="x")
    ttk.Label(header, text="MCCP  /  COMPANION BRAIN", font=("Segoe UI", 19, "bold")).pack(side="left")
    status_var = tk.StringVar(value="Starting daemon...")
    ttk.Label(header, textvariable=status_var).pack(side="right")
    toolbar = ttk.Frame(root, padding=(15, 2, 15, 10))
    toolbar.pack(fill="x")
    notebook = ttk.Notebook(root)
    notebook.pack(fill="both", expand=True, padx=15, pady=(0, 15))
    panels = {}
    for title in ("Dashboard", "Brain & Model", "Goals & Actions", "Connection"):
        panel = ttk.Frame(notebook, padding=18)
        notebook.add(panel, text=title)
        panels[title] = panel

    def action(callable_):
        try:
            return callable_()
        except Exception as exc:
            messagebox.showerror("MCCP", str(exc))
            return None

    def post(path, body):
        return api("POST", "/api/v1/" + path, body, timeout=55 if path == "message" else 3)

    def start_clicked():
        start_daemon()
        root.after(800, refresh)

    def control(operation):
        action(lambda: post("control", {"operation": operation}))
        refresh()

    ttk.Button(toolbar, text="Start / Reconnect", command=start_clicked).pack(side="left", padx=4)
    ttk.Button(toolbar, text="Pause Autonomy", command=lambda: control("pause")).pack(side="left", padx=4)
    ttk.Button(toolbar, text="Resume", command=lambda: control("resume")).pack(side="left", padx=4)

    dash = panels["Dashboard"]
    ttk.Label(dash, text="Brain runtime", font=("Segoe UI", 16, "bold")).pack(anchor="w", pady=(0, 8))
    info_var = tk.StringVar(value="Waiting for brain daemon...")
    ttk.Label(dash, textvariable=info_var).pack(anchor="w", pady=(0, 15))
    ttk.Label(dash, text="Live event stream").pack(anchor="w")
    event_view = tk.Text(dash, bg="#172530", fg="#d4e5ec", relief="flat",
                         font=("Consolas", 10), state="disabled", wrap="word")
    event_view.pack(fill="both", expand=True, pady=8)
    ttk.Label(dash, text="The brain keeps running if this window closes. Actions always require review.").pack(anchor="w")

    brain_panel = panels["Brain & Model"]
    ttk.Label(brain_panel, text="Runtime modules", font=("Segoe UI", 15, "bold")).pack(anchor="w")
    toggles = {}
    for module in DEFAULT_CONFIG["modules"]:
        variable = tk.BooleanVar(value=DEFAULT_CONFIG["modules"][module])
        toggles[module] = variable
        ttk.Checkbutton(brain_panel, text=module.replace("_", " ").title(),
                        variable=variable, command=lambda m=module, v=variable:
                        action(lambda: post("config", {"modules": {m: v.get()}}))).pack(anchor="w", pady=3)
    autonomy_var = tk.DoubleVar(value=50)
    ttk.Label(brain_panel, text="Autonomy level (0–100)").pack(anchor="w", pady=(12, 0))
    ttk.Scale(brain_panel, from_=0, to=100, orient="horizontal", variable=autonomy_var).pack(fill="x")
    ttk.Button(brain_panel, text="Apply autonomy",
               command=lambda: action(lambda: post("config", {"autonomy": int(autonomy_var.get())}))).pack(anchor="w", pady=8)
    allow_var = tk.BooleanVar(value=False)
    ttk.Checkbutton(brain_panel, text="Permit approved world-changing actions",
                    variable=allow_var,
                    command=lambda: action(lambda: post("config", {"allow_world_actions": allow_var.get()}))).pack(anchor="w")
    row = ttk.Frame(brain_panel)
    row.pack(fill="x", pady=12)
    ttk.Label(row, text="Ollama model:").pack(side="left", padx=5)
    model_var = tk.StringVar(value="qwen3:4b")
    ttk.Entry(row, textvariable=model_var, width=30).pack(side="left", padx=4)
    ttk.Button(row, text="Save model", command=lambda: action(
        lambda: post("config", {"model": model_var.get()}))).pack(side="left", padx=5)
    chat_row = ttk.Frame(brain_panel)
    chat_row.pack(fill="x", pady=4)
    chat_input = ttk.Entry(chat_row)
    chat_input.pack(side="left", fill="x", expand=True)
    chat_output = tk.Text(brain_panel, height=9, bg="#172530", fg="#d4e5ec", wrap="word")
    chat_output.pack(fill="both", expand=True, pady=6)

    def send_chat():
        message = chat_input.get().strip()
        if not message:
            return
        chat_input.delete(0, "end")
        chat_output.insert("end", "\nYou: " + message + "\n")
        def task():
            try:
                result = post("message", {"text": message})
                reply = result["reply"]
            except Exception as exc:
                reply = "[Error] " + str(exc)
            root.after(0, lambda: (chat_output.insert("end", "MCCP: " + reply + "\n"),
                                   chat_output.see("end")))
        threading.Thread(target=task, daemon=True).start()

    ttk.Button(chat_row, text="Send to Local Model", command=send_chat).pack(side="left", padx=5)
    chat_input.bind("<Return>", lambda event: send_chat())

    goals_panel = panels["Goals & Actions"]
    ttk.Label(goals_panel, text="Independent goals", font=("Segoe UI", 15, "bold")).pack(anchor="w")
    goal_row = ttk.Frame(goals_panel)
    goal_row.pack(fill="x", pady=9)
    goal_input = ttk.Entry(goal_row)
    goal_input.pack(side="left", fill="x", expand=True)
    def create_goal():
        text = goal_input.get().strip()
        if text:
            result = action(lambda: post("goals", {"text": text}))
            if result:
                goal_input.delete(0, "end")
                refresh()
    ttk.Button(goal_row, text="Add Goal", command=create_goal).pack(side="left", padx=5)
    goal_list = tk.Listbox(goals_panel, height=7, bg="#172530", fg="#d4e5ec",
                           selectbackground="#2c637e")
    goal_list.pack(fill="x")
    goal_ids = []
    def finish_goal():
        selection = goal_list.curselection()
        if selection and selection[0] < len(goal_ids):
            action(lambda: post("goals/status", {"id": goal_ids[selection[0]], "status": "done"}))
            refresh()
    ttk.Button(goals_panel, text="Mark Selected Goal Done", command=finish_goal).pack(anchor="w", pady=8)
    ttk.Label(goals_panel, text="Action approval queue", font=("Segoe UI", 14, "bold")).pack(anchor="w")
    proposal_list = tk.Listbox(goals_panel, height=9, bg="#172530", fg="#d4e5ec",
                               selectbackground="#2c637e")
    proposal_list.pack(fill="both", expand=True, pady=6)
    proposal_ids = []
    review_row = ttk.Frame(goals_panel)
    review_row.pack(fill="x")
    def review(approved):
        selection = proposal_list.curselection()
        if selection and selection[0] < len(proposal_ids):
            action(lambda: post("actions/review", {"id": proposal_ids[selection[0]], "approved": approved}))
            refresh()
    ttk.Button(review_row, text="Approve Selected", command=lambda: review(True)).pack(side="left", padx=4)
    ttk.Button(review_row, text="Reject Selected", command=lambda: review(False)).pack(side="left", padx=4)

    connect_panel = panels["Connection"]
    ttk.Label(connect_panel, text="Local game bridge", font=("Segoe UI", 16, "bold")).pack(anchor="w", pady=(0, 12))
    ttk.Label(connect_panel, text="HTTP endpoint: http://127.0.0.1:" + str(PORT) + "/api/v1").pack(anchor="w", pady=4)
    ttk.Label(connect_panel, text="Supply X-MCCP-Token on every game bridge request.").pack(anchor="w", pady=4)
    ttk.Label(connect_panel, text="The token is stored locally and is never sent to any cloud service.").pack(anchor="w", pady=4)
    def copy_token():
        root.clipboard_clear()
        root.clipboard_append(get_or_create_token())
        root.update()
        messagebox.showinfo("MCCP", "API token copied to clipboard.")
    ttk.Button(connect_panel, text="Copy API Token", command=copy_token).pack(anchor="w", pady=10)
    ttk.Label(connect_panel, text="Minecraft integration not yet implemented. Inject simulated observations below.").pack(anchor="w", pady=8)
    def simulate():
        action(lambda: post("observe", {
            "kind": "player_nearby", "payload": {
                "dimension": "minecraft:overworld", "player": "TestPlayer",
                "position": {"x": 12, "y": 70, "z": -4}
            }
        }))
        refresh()
    ttk.Button(connect_panel, text="Simulate Player Nearby", command=simulate).pack(anchor="w", pady=6)

    def refresh():
        try:
            state = api("GET", "/api/v1/state", timeout=0.5)
        except Exception:
            status_var.set("OFFLINE  •  daemon unavailable")
            info_var.set("No brain process is responding. Press Start / Reconnect.")
            root.after(1600, refresh)
            return
        status_var.set(("PAUSED" if state["paused"] else "RUNNING") +
                       ("  •  GAME CONNECTED" if state["connected"] else "  •  GAME OFFLINE"))
        info_var.set("Model: " + state["config"]["model"] + "   |   Goals: " +
                     str(len(state["goals"])) + "   |   Observations: " +
                     str(state["observation_count"]) + "   |   Queued actions: " +
                     str(state["queued_actions"]))
        event_view.configure(state="normal")
        event_view.delete("1.0", "end")
        for entry in state["events"][-60:]:
            when = time.strftime("%H:%M:%S", time.localtime(entry["time"]))
            event_view.insert("end", "[" + when + "] " + entry["kind"].upper() + ": " + entry["text"] + "\n")
        event_view.configure(state="disabled")
        event_view.see("end")
        goal_list.delete(0, "end")
        goal_ids.clear()
        for goal in state["goals"]:
            goal_ids.append(goal["id"])
            goal_list.insert("end", "[" + goal["status"] + "] " + goal["text"])
        proposal_list.delete(0, "end")
        proposal_ids.clear()
        for proposal in state["proposals"]:
            if proposal["status"] == "pending":
                proposal_ids.append(proposal["id"])
                proposal_list.insert("end", proposal["kind"] + " : " +
                                     json.dumps(proposal["arguments"])[:150])
        for name, var in toggles.items():
            if root.focus_get() is not None:
                var.set(state["config"]["modules"][name])
        allow_var.set(state["config"]["allow_world_actions"])
        root.after(1600, refresh)

    root.after(1100, refresh)
    root.mainloop()


def main() -> None:
    if "--daemon" in sys.argv:
        serve()
    else:
        launch_gui()


if __name__ == "__main__":
    main()
