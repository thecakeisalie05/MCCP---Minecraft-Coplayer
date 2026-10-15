"""MCCP Companion Viewer: live telemetry and explicit manual control of the AI avatar.

All commands flow through the same versioned, authenticated brain API planned for AI
skill calls. Does not assume a game connection exists and never takes over the human.
"""
from __future__ import annotations

import json
import sys
import threading
import time
import tkinter as tk
from tkinter import messagebox, ttk
from tkinter.scrolledtext import ScrolledText

from mccp import api, APP_VERSION

POLL_SECONDS = 1.0
WORLD_ACTIONS = {"spawn", "despawn", "move_to", "look_at", "follow",
                 "move_relative", "turn_by", "jump"}


class Viewer:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("MCCP Viewer " + APP_VERSION + " | Independent Coplayer")
        self.root.geometry("1240x830")
        self.root.minsize(940, 650)
        self.root.configure(bg="#111b26")
        self.busy = False
        self.image = None
        self.last_frame = None
        self.last_timestamp = None
        self.policy = tk.BooleanVar(value=False)
        self.auto_follow = tk.StringVar(value="")
        self.status = tk.StringVar(value="Connecting to local MCCP brain...")
        self.pose = tk.StringVar(value="Position: waiting")
        self.stats = tk.StringVar(value="Health: --   Hunger: --   Dimension: --")
        self.counts = tk.StringVar(value="Blocks: 0 | Entities: 0 | Inventory: 0")
        self.x = tk.StringVar(value="0")
        self.y = tk.StringVar(value="65")
        self.z = tk.StringVar(value="0")
        self.message = tk.StringVar(value="Hello! I'm ready to help.")
        style = ttk.Style(self.root)
        style.theme_use("clam")
        style.configure(".", background="#111b26", foreground="#e5eef5", font=("Segoe UI", 10))
        style.configure("TFrame", background="#111b26")
        style.configure("TLabel", background="#111b26", foreground="#e5eef5")
        style.configure("TButton", padding=(10, 7), background="#23415a", foreground="#f1f5fa")
        style.map("TButton", background=[("active", "#385e79")])
        style.configure("TEntry", fieldbackground="#203344", foreground="#eff7ff")
        style.configure("TCheckbutton", background="#111b26", foreground="#e5eef5")
        style.configure("TNotebook", background="#111b26")
        style.configure("TNotebook.Tab", background="#1d3244", foreground="#e5eef5", padding=(12, 9))
        style.map("TNotebook.Tab", background=[("selected", "#315776")])
        header = ttk.Frame(self.root, padding=14)
        header.pack(fill="x")
        ttk.Label(header, text="MCCP  /  COMPANION OBSERVER", font=("Segoe UI", 19, "bold")).pack(side="left")
        ttk.Label(header, textvariable=self.status).pack(side="right")
        ttk.Label(self.root, textvariable=self.pose, font=("Consolas", 12)).pack(fill="x", padx=16, pady=2)
        ttk.Label(self.root, textvariable=self.stats).pack(fill="x", padx=16)
        ttk.Label(self.root, textvariable=self.counts).pack(fill="x", padx=16, pady=(2, 6))
        split = ttk.PanedWindow(self.root, orient=tk.HORIZONTAL)
        split.pack(fill="both", expand=True, padx=12, pady=10)
        left = ttk.Frame(split, padding=8)
        right = ttk.Frame(split, padding=8)
        split.add(left, weight=2)
        split.add(right, weight=3)
        ttk.Label(left, text="Companion perspective", font=("Segoe UI", 13, "bold")).pack(anchor="w")
        self.frame_label = ttk.Label(left, text="No companion image received.", anchor="center",
                                     width=54)
        self.frame_label.pack(fill="x", pady=12, ipady=75)
        self.frame_kind = ttk.Label(left, text="Frame type: --")
        self.frame_kind.pack(anchor="w", pady=(0, 12))
        ttk.Label(left, text="Manual controls", font=("Segoe UI", 13, "bold")).pack(anchor="w", pady=5)
        ttk.Checkbutton(left, variable=self.policy, text="Enable approved world-changing commands",
                        command=self.change_policy).pack(anchor="w", pady=(4, 10))
        row = ttk.Frame(left)
        row.pack(fill="x", pady=4)
        for label, kind in (("Spawn", "spawn"), ("Despawn", "despawn"),
                            ("Stop", "stop")):
            ttk.Button(row, text=label, command=lambda k=kind: self.send(k, {})).pack(side="left", padx=3)
        steer = ttk.Frame(left)
        steer.pack(fill="x", pady=6)
        ttk.Label(steer, text="Step controls:").grid(row=0, column=0, columnspan=5, sticky="w")
        buttons = (
            (1, 0, "Turn left", "turn_by", {"degrees": -30}),
            (1, 1, "Forward", "move_relative", {"forward": 1, "blocks": 2}),
            (1, 2, "Jump", "jump", {}),
            (1, 3, "Turn right", "turn_by", {"degrees": 30}),
            (2, 0, "Left", "move_relative", {"strafe": -1, "blocks": 2}),
            (2, 1, "Back", "move_relative", {"forward": -1, "blocks": 2}),
            (2, 2, "Right", "move_relative", {"strafe": 1, "blocks": 2}),
        )
        for rownum, colnum, label, kind, args in buttons:
            ttk.Button(steer, text=label,
                       command=lambda k=kind, a=args: self.send(k, a)).grid(
                           row=rownum, column=colnum, padx=2, pady=3)
        coord = ttk.Frame(left)
        coord.pack(fill="x", pady=8)
        for label, var in (("X", self.x), ("Y", self.y), ("Z", self.z)):
            ttk.Label(coord, text=label).pack(side="left", padx=(6, 0))
            ttk.Entry(coord, textvariable=var, width=9).pack(side="left", padx=3)
        row2 = ttk.Frame(left)
        row2.pack(fill="x")
        ttk.Button(row2, text="Move To XYZ",
                   command=lambda: self.coordinate_command("move_to")).pack(side="left", padx=3)
        ttk.Button(row2, text="Look At XYZ",
                   command=lambda: self.coordinate_command("look_at")).pack(side="left", padx=3)
        follow = ttk.Frame(left)
        follow.pack(fill="x", pady=8)
        ttk.Label(follow, text="Player to follow (blank = first online):").pack(anchor="w")
        ttk.Entry(follow, textvariable=self.auto_follow, width=26).pack(side="left", padx=3)
        ttk.Button(follow, text="Follow Once",
                   command=lambda: self.send("follow", {"player": self.auto_follow.get()})).pack(side="left", padx=3)
        say = ttk.Frame(left)
        say.pack(fill="x", pady=8)
        ttk.Label(left, text="Say in Minecraft chat:").pack(anchor="w")
        ttk.Entry(say, textvariable=self.message).pack(side="left", fill="x", expand=True, padx=3)
        ttk.Button(say, text="Say", command=lambda: self.send("say", {
            "text": self.message.get()[:240]
        })).pack(side="left")
        self.feedback = ScrolledText(left, height=8, bg="#152838", fg="#d8eaf3",
                                     wrap="word", font=("Consolas", 9))
        self.feedback.pack(fill="both", expand=True, pady=12)
        self.feedback.insert("end", "Control commands are proposed, approved and queued.\n"
                             "A queued action is not proof that the game executed it.\n")
        self.feedback.configure(state="disabled")
        tabs = ttk.Notebook(right)
        tabs.pack(fill="both", expand=True)
        self.views = {}
        for name in ("Visible Blocks", "Entities", "Inventory", "Raw Telemetry"):
            frame = ttk.Frame(tabs, padding=5)
            tabs.add(frame, text=name)
            view = ScrolledText(frame, bg="#152838", fg="#d8eaf3",
                                font=("Consolas", 9), wrap="none")
            view.pack(fill="both", expand=True)
            self.views[name] = view
        self.root.after(300, self.refresh)

    def log(self, line):
        self.feedback.configure(state="normal")
        self.feedback.insert("end", "[" + time.strftime("%H:%M:%S") + "] " + line + "\n")
        self.feedback.see("end")
        self.feedback.configure(state="disabled")

    def background(self, work, after):
        def thread():
            try:
                result = work()
                self.root.after(0, lambda: after(result, None))
            except Exception as error:
                self.root.after(0, lambda: after(None, str(error)))
        threading.Thread(target=thread, daemon=True).start()

    def change_policy(self):
        checked = self.policy.get()
        def after(result, error):
            if error:
                self.policy.set(not checked)
                self.log("Policy change failed: " + error)
            else:
                self.log("World-changing actions " + ("enabled" if checked else "disabled"))
        self.background(lambda: api("POST", "/api/v1/config",
                        {"allow_world_actions": checked}, timeout=3), after)

    def coordinate_command(self, kind):
        try:
            xyz = {"x": float(self.x.get()), "y": float(self.y.get()), "z": float(self.z.get())}
            if not all(-30000000 <= xyz[k] <= 30000000 for k in xyz):
                raise ValueError("Coordinates exceed valid Minecraft range")
        except ValueError:
            messagebox.showerror("Invalid coordinates", "Enter valid numerical X, Y and Z.")
            return
        self.send(kind, xyz)

    def send(self, kind, arguments):
        if kind in WORLD_ACTIONS and not self.policy.get():
            messagebox.showwarning("Actions disabled",
                "Check 'Enable approved world-changing commands' before controlling the avatar.")
            return
        if kind == "despawn" and not messagebox.askyesno(
                "Despawn companion", "Remove the MCCP companion from the world?"):
            return
        self.log("Submitting manual " + kind + " command...")
        def work():
            proposal = api("POST", "/api/v1/actions/propose",
                           {"kind": kind, "arguments": arguments}, timeout=4)
            return api("POST", "/api/v1/actions/review", {
                "id": proposal["id"], "approved": True
            }, timeout=4)
        self.background(work, lambda result, error: self.log(
            ("Command failed: " + error) if error else
            (kind + " " + result["status"] + " / queued for delivery; not yet executed.")))

    def refresh(self):
        if self.busy:
            self.root.after(int(POLL_SECONDS * 1000), self.refresh)
            return
        self.busy = True
        def work():
            state = api("GET", "/api/v1/game/state", timeout=3)
            config = api("GET", "/api/v1/state", timeout=3)
            return state, config
        def after(value, error):
            self.busy = False
            if error:
                self.status.set("OFFLINE / " + error[:80])
            else:
                state, config = value
                self.policy.set(bool(config.get("config", {}).get("allow_world_actions", False)))
                self.apply_state(state)
            self.root.after(int(POLL_SECONDS * 1000), self.refresh)
        self.background(work, after)

    def apply_state(self, state):
        if not isinstance(state.get("pose"), dict):
            self.status.set("Waiting for companion / telemetry")
            return
        self.status.set("LIVE / companion " + str(state.get("companion_id", ""))[:8])
        pos = state["pose"]
        stats = state.get("stats") or {}
        self.pose.set("XYZ: %.2f %.2f %.2f    yaw %.1f° / pitch %.1f°    heading %s" % (
            pos.get("x", 0), pos.get("y", 0), pos.get("z", 0),
            pos.get("yaw", 0), pos.get("pitch", 0), pos.get("heading", "unknown")))
        self.stats.set("HP %s/%s  |  Hunger %s  |  Air %s  |  Dimension %s" % (
            stats.get("health", "--"), stats.get("max_health", "--"),
            stats.get("hunger", "--"), stats.get("air", "--"),
            state.get("dimension", "--")))
        blocks = state.get("blocks", [])
        entities = state.get("entities", [])
        items = state.get("inventory", [])
        self.counts.set("Blocks %d%s | Entities %d | Inventory %d" % (
            len(blocks), " (truncated)" if state.get("blocks_truncated") else "",
            len(entities), len(items)))
        self.frame_kind.configure(text="Frame: " + state.get("frame_kind", "none") +
                                  " | Not a GPU screenshot")
        stamp = state.get("timestamp_ms")
        if stamp != self.last_timestamp:
            self.last_timestamp = stamp
            for name, content in (
                ("Visible Blocks", blocks),
                ("Entities", entities),
                ("Inventory", items),
                ("Raw Telemetry", {k: v for k, v in state.items() if k != "frame_png_base64"})
            ):
                view = self.views[name]
                view.delete("1.0", "end")
                view.insert("end", json.dumps(content, indent=2, ensure_ascii=False))
            frame = state.get("frame_png_base64")
            if frame and frame != self.last_frame:
                try:
                    native = tk.PhotoImage(data=frame, format="png")
                    self.image = native.zoom(8, 8)
                    self.frame_label.configure(image=self.image, text="")
                    self.last_frame = frame
                except tk.TclError as error:
                    self.frame_label.configure(text="PNG decode failed: " + str(error), image="")

    def run(self):
        self.root.mainloop()


if __name__ == "__main__":
    Viewer().run()
