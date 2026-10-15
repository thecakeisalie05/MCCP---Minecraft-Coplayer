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

POLL_SECONDS = 0.12
WORLD_ACTIONS = {"spawn", "despawn", "move_to", "look_at", "follow",
                 "move_relative", "turn_by", "jump"}


# Charcoal workbench theme: compact, high-contrast and dockable like an IDE.
PALETTE = {
    "window": "#0B111B",
    "panel": "#121D2A",
    "raised": "#1B2A3B",
    "field": "#101B28",
    "border": "#304358",
    "text": "#E9F1F8",
    "muted": "#93A7B8",
    "accent": "#43D2E9",
    "accent_dark": "#14394A",
    "warning": "#EDAA76",
    "danger": "#E78C91",
}


def camera_scale(source_width, source_height, available_width, available_height):
    """Fit a small pixel-art frame without distortion or fractional scaling."""
    if min(source_width, source_height, available_width, available_height) <= 0:
        return 1
    return max(1, min(12, available_width // source_width, available_height // source_height))


class ScrollPanel(ttk.Frame):
    """The control deck scrolls at all window sizes, independently of the camera."""

    def __init__(self, parent):
        super().__init__(parent)
        self.canvas = tk.Canvas(self, bg=PALETTE["panel"], highlightthickness=0, borderwidth=0)
        self.scrollbar = ttk.Scrollbar(self, orient="vertical", command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=self.scrollbar.set)
        self.scrollbar.pack(side="right", fill="y")
        self.canvas.pack(side="left", fill="both", expand=True)
        self.content = ttk.Frame(self.canvas, style="Panel.TFrame", padding=(12, 10))
        self.window_id = self.canvas.create_window((0, 0), window=self.content, anchor="nw")
        self.content.bind("<Configure>", self._update_scrollregion)
        self.canvas.bind("<Configure>", self._resize_width)
        self.canvas.bind("<Enter>", self._wheel_enter)
        self.canvas.bind("<Leave>", self._wheel_leave)

    def _update_scrollregion(self, event=None):
        self.canvas.configure(scrollregion=self.canvas.bbox("all"))

    def _resize_width(self, event):
        self.canvas.itemconfigure(self.window_id, width=event.width)

    def _wheel_enter(self, event=None):
        self.canvas.bind_all("<MouseWheel>", self._wheel, add="+")
        self.canvas.bind_all("<Button-4>", self._wheel_linux, add="+")
        self.canvas.bind_all("<Button-5>", self._wheel_linux, add="+")

    def _wheel_leave(self, event=None):
        self.canvas.unbind_all("<MouseWheel>")
        self.canvas.unbind_all("<Button-4>")
        self.canvas.unbind_all("<Button-5>")

    def _wheel(self, event):
        self.canvas.yview_scroll(-int(event.delta / 120) or (-1 if event.delta > 0 else 1), "units")

    def _wheel_linux(self, event):
        self.canvas.yview_scroll(-1 if event.num == 4 else 1, "units")


class Viewer:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("MCCP / Companion Workbench")
        self.root.geometry("1340x830")
        self.root.minsize(810, 530)
        self.root.configure(bg=PALETTE["window"])
        self.root.grid_rowconfigure(2, weight=1)
        self.root.grid_columnconfigure(0, weight=1)

        self.busy = False
        self.image = None
        self.raw_image = None
        self.last_frame = None
        self.last_timestamp = None
        self.last_refresh_error = None
        self.preview_loaded = False
        self.frame_tick = None
        self.preview_backend = tk.StringVar(value="cpu")
        self.preview_width = tk.StringVar(value="64 × 36")
        self.preview_fps = tk.StringVar(value="2")
        self.policy = tk.BooleanVar(value=False)
        self.auto_follow = tk.StringVar(value="")
        self.status = tk.StringVar(value="CONNECTING TO LOCAL BRAIN")
        self.pose = tk.StringVar(value="WAITING FOR TELEMETRY")
        self.stats = tk.StringVar(value="HEALTH --/--   ·   FOOD --   ·   AIR --")
        self.counts = tk.StringVar(value="BLOCKS 0   ·   ENTITIES 0   ·   ITEMS 0")
        self.x = tk.StringVar(value="0")
        self.y = tk.StringVar(value="65")
        self.z = tk.StringVar(value="0")
        self.message = tk.StringVar(value="Hello! I'm ready to help.")

        self._styles()
        self._header()
        self._metrics()
        self._workspace()
        self._footer()
        self.root.after(300, self.refresh)

    def _styles(self):
        style = ttk.Style(self.root)
        style.theme_use("clam")
        style.configure(".", font=("Segoe UI", 10), background=PALETTE["window"],
                        foreground=PALETTE["text"])
        style.configure("TFrame", background=PALETTE["window"])
        style.configure("Panel.TFrame", background=PALETTE["panel"])
        style.configure("Raised.TFrame", background=PALETTE["raised"])
        style.configure("TLabel", background=PALETTE["window"], foreground=PALETTE["text"])
        style.configure("Panel.TLabel", background=PALETTE["panel"], foreground=PALETTE["text"])
        style.configure("Muted.TLabel", background=PALETTE["panel"], foreground=PALETTE["muted"])
        style.configure("Caption.TLabel", background=PALETTE["window"], foreground=PALETTE["muted"])
        style.configure("Brand.TLabel", background=PALETTE["window"],
                        foreground=PALETTE["text"], font=("Segoe UI Semibold", 17))
        style.configure("Status.TLabel", background=PALETTE["window"],
                        foreground=PALETTE["accent"], font=("Consolas", 10))
        style.configure("Metric.TLabel", background=PALETTE["panel"],
                        foreground=PALETTE["text"], font=("Consolas", 10))
        style.configure("Heading.TLabel", background=PALETTE["panel"],
                        foreground=PALETTE["text"], font=("Segoe UI Semibold", 11))
        style.configure("TButton", background=PALETTE["raised"],
                        foreground=PALETTE["text"], padding=(10, 6), relief="flat",
                        borderwidth=0)
        style.map("TButton",
                  background=[("disabled", PALETTE["panel"]), ("active", PALETTE["border"])],
                  foreground=[("disabled", PALETTE["muted"])])
        style.configure("Accent.TButton", background=PALETTE["accent_dark"],
                        foreground=PALETTE["accent"], padding=(10, 7),
                        relief="flat", borderwidth=0)
        style.map("Accent.TButton", background=[("active", "#24566A")])
        style.configure("Danger.TButton", background="#422934",
                        foreground="#FFB5BB", padding=(10, 7), relief="flat",
                        borderwidth=0)
        style.map("Danger.TButton", background=[("active", "#5B3440")])
        style.configure("TEntry", fieldbackground=PALETTE["field"],
                        foreground=PALETTE["text"], insertcolor=PALETTE["text"],
                        padding=5, bordercolor=PALETTE["border"])
        style.configure("TCombobox", fieldbackground=PALETTE["field"],
                        foreground=PALETTE["text"], arrowcolor=PALETTE["accent"],
                        selectbackground=PALETTE["raised"],
                        padding=4)
        style.map("TCombobox", fieldbackground=[("readonly", PALETTE["field"])],
                  foreground=[("readonly", PALETTE["text"])])
        style.configure("TCheckbutton", background=PALETTE["panel"],
                        foreground=PALETTE["text"])
        style.map("TCheckbutton", background=[("active", PALETTE["panel"])])
        style.configure("TNotebook", background=PALETTE["panel"],
                        borderwidth=0, tabmargins=(0, 0, 0, 0))
        style.configure("TNotebook.Tab", background=PALETTE["panel"],
                        foreground=PALETTE["muted"], padding=(16, 11), borderwidth=0)
        style.map("TNotebook.Tab", background=[("selected", PALETTE["raised"]),
                                              ("active", PALETTE["raised"])],
                  foreground=[("selected", PALETTE["accent"])])
        style.configure("TScrollbar", background=PALETTE["raised"],
                        troughcolor=PALETTE["panel"], arrowcolor=PALETTE["muted"])
        style.configure("TPanedwindow", background=PALETTE["border"])

    def _header(self):
        top = ttk.Frame(self.root, padding=(18, 10, 18, 10))
        top.grid(row=0, column=0, sticky="ew")
        top.columnconfigure(1, weight=1)
        ttk.Label(top, text="MCCP", style="Brand.TLabel").grid(row=0, column=0, sticky="w")
        ttk.Label(top, text="  /  COMPANION WORKBENCH", style="Caption.TLabel").grid(
            row=0, column=1, sticky="w", padx=(4, 0))
        ttk.Label(top, textvariable=self.status, style="Status.TLabel",
                  anchor="e", width=46).grid(row=0, column=2, sticky="e")

    def _metrics(self):
        strip = ttk.Frame(self.root, padding=(12, 0, 12, 8))
        strip.grid(row=1, column=0, sticky="ew")
        for column in range(3):
            strip.columnconfigure(column, weight=1, uniform="metrics")
        self._metric(strip, "POSITION / ORIENTATION", self.pose, 0)
        self._metric(strip, "COMPANION VITALS", self.stats, 1)
        self._metric(strip, "SCENE DATA", self.counts, 2)

    def _metric(self, parent, heading, variable, column):
        card = ttk.Frame(parent, style="Panel.TFrame", padding=(13, 9))
        card.grid(row=0, column=column, sticky="nsew", padx=3)
        ttk.Label(card, text=heading, style="Muted.TLabel",
                  font=("Segoe UI", 8)).pack(anchor="w")
        ttk.Label(card, textvariable=variable, style="Metric.TLabel",
                  anchor="w").pack(anchor="w", pady=(4, 0), fill="x")

    def _workspace(self):
        main = ttk.Panedwindow(self.root, orient=tk.HORIZONTAL)
        main.grid(row=2, column=0, sticky="nsew", padx=15, pady=(4, 8))
        camera = ttk.Frame(main, style="Panel.TFrame", padding=12)
        deck = ttk.Frame(main, style="Panel.TFrame", padding=10)
        main.add(camera, weight=3)
        main.add(deck, weight=2)

        camera.columnconfigure(0, weight=1)
        camera.rowconfigure(1, weight=1)
        cap = ttk.Frame(camera, style="Panel.TFrame")
        cap.grid(row=0, column=0, sticky="ew", pady=(0, 9))
        ttk.Label(cap, text="COMPANION CAMERA", style="Heading.TLabel").pack(side="left")
        self.frame_kind = ttk.Label(cap, text="NO SIGNAL",
                                    style="Muted.TLabel", anchor="e")
        self.frame_kind.pack(side="right")
        self.camera_canvas = tk.Canvas(
            camera, bg="#060B10", highlightthickness=1,
            highlightbackground=PALETTE["border"], relief="flat", bd=0
        )
        self.camera_canvas.grid(row=1, column=0, sticky="nsew")
        self.camera_canvas.bind("<Configure>", self._canvas_resized)
        self._camera_placeholder()

        preview = ttk.Frame(camera, style="Panel.TFrame", padding=(0, 13, 0, 0))
        preview.grid(row=2, column=0, sticky="ew")
        ttk.Label(preview, text="PREVIEW", style="Muted.TLabel",
                  font=("Segoe UI Semibold", 9)).grid(row=0, column=0, sticky="w")
        for col in (0, 1, 2):
            preview.columnconfigure(col, weight=1)
        ttk.Label(preview, text="Backend", style="Muted.TLabel").grid(
            row=1, column=0, sticky="w", pady=(9, 3))
        ttk.Label(preview, text="Resolution", style="Muted.TLabel").grid(
            row=1, column=1, sticky="w", pady=(9, 3))
        ttk.Label(preview, text="Target FPS", style="Muted.TLabel").grid(
            row=1, column=2, sticky="w", pady=(9, 3))
        ttk.Combobox(preview, textvariable=self.preview_backend, state="readonly",
                     values=("cpu", "gpu"), width=8).grid(row=2, column=0, sticky="ew", padx=(0, 8))
        ttk.Combobox(preview, textvariable=self.preview_width, state="readonly",
                     values=("48 × 27", "64 × 36", "80 × 45", "96 × 54", "128 × 72"),
                     width=12).grid(row=2, column=1, sticky="ew", padx=(0, 8))
        ttk.Combobox(preview, textvariable=self.preview_fps, state="readonly",
                     values=tuple(str(i) for i in range(1, 9)), width=7).grid(
            row=2, column=2, sticky="ew", padx=(0, 8))
        ttk.Button(preview, text="Apply preview settings", style="Accent.TButton",
                   command=self.apply_preview).grid(row=3, column=0, columnspan=3,
                                                    sticky="ew", pady=(10, 0))
        ttk.Label(preview, text="GPU mode uses a simplified voxel shader, not a full game camera.",
                  style="Muted.TLabel", font=("Segoe UI", 8)).grid(
            row=4, column=0, columnspan=3, sticky="w", pady=(6, 0))

        deck.columnconfigure(0, weight=1)
        deck.rowconfigure(2, weight=1)
        controls_heading = ttk.Frame(deck, style="Panel.TFrame")
        controls_heading.grid(row=0, column=0, sticky="ew")
        ttk.Label(controls_heading, text="COMMAND DECK", style="Heading.TLabel").pack(
            side="left")
        ttk.Label(controls_heading, text="MANUAL / LOCAL", style="Muted.TLabel",
                  font=("Consolas", 8)).pack(side="right")

        # Critical buttons and permission state never scroll away.
        quick = ttk.Frame(deck, style="Panel.TFrame")
        quick.grid(row=1, column=0, sticky="ew", pady=(12, 11))
        for col in range(3):
            quick.columnconfigure(col, weight=1, uniform="quick")
        ttk.Button(quick, text="SPAWN", style="Accent.TButton",
                   command=lambda: self.send("spawn", {})).grid(
            row=0, column=0, sticky="ew", padx=(0, 4))
        ttk.Button(quick, text="STOP", command=lambda: self.send("stop", {})).grid(
            row=0, column=1, sticky="ew", padx=4)
        ttk.Button(quick, text="DESPAWN", style="Danger.TButton",
                   command=lambda: self.send("despawn", {})).grid(
            row=0, column=2, sticky="ew", padx=(4, 0))
        ttk.Checkbutton(quick, variable=self.policy,
                        text="Enable approved world-changing commands",
                        command=self.change_policy).grid(
            row=1, column=0, columnspan=3, sticky="w", pady=(10, 0))

        tabs = ttk.Notebook(deck)
        tabs.grid(row=2, column=0, sticky="nsew")
        control_host = ttk.Frame(tabs, style="Panel.TFrame")
        inspect_host = ttk.Frame(tabs, style="Panel.TFrame")
        activity_host = ttk.Frame(tabs, style="Panel.TFrame")
        tabs.add(control_host, text="CONTROL")
        tabs.add(inspect_host, text="INSPECT")
        tabs.add(activity_host, text="ACTIVITY")
        self.tabs = tabs
        control_host.columnconfigure(0, weight=1)
        control_host.rowconfigure(0, weight=1)
        panel = ScrollPanel(control_host)
        panel.grid(row=0, column=0, sticky="nsew")
        self.control_scroll = panel
        self._build_controls(panel.content)
        self._build_inspect(inspect_host)
        self._build_activity(activity_host)

    def _section(self, parent, name):
        frame = ttk.Frame(parent, style="Panel.TFrame")
        frame.pack(fill="x", pady=(3, 14))
        ttk.Label(frame, text=name, style="Heading.TLabel").pack(
            anchor="w", pady=(0, 9))
        return frame

    def _build_controls(self, parent):
        movement = self._section(parent, "Movement & orientation")
        directional = ttk.Frame(movement, style="Panel.TFrame")
        directional.pack(fill="x")
        for col in range(3):
            directional.columnconfigure(col, weight=1, uniform="direction")
        buttons = (
            (0, 0, "↶  30°", "turn_by", {"degrees": -30}),
            (0, 1, "↑  Forward", "move_relative", {"forward": 1, "blocks": 2}),
            (0, 2, "30°  ↷", "turn_by", {"degrees": 30}),
            (1, 0, "←  Strafe", "move_relative", {"strafe": -1, "blocks": 2}),
            (1, 1, "↓  Back", "move_relative", {"forward": -1, "blocks": 2}),
            (1, 2, "Strafe  →", "move_relative", {"strafe": 1, "blocks": 2}),
        )
        for row, col, label, kind, args in buttons:
            ttk.Button(directional, text=label,
                       command=lambda k=kind, a=args: self.send(k, a)).grid(
                row=row, column=col, sticky="ew", padx=3, pady=3)
        ttk.Button(movement, text="JUMP", command=lambda: self.send("jump", {})).pack(
            fill="x", padx=3, pady=(4, 0))

        destination = self._section(parent, "World coordinates")
        xyz = ttk.Frame(destination, style="Panel.TFrame")
        xyz.pack(fill="x")
        for col, (label, var) in enumerate((("X", self.x), ("Y", self.y), ("Z", self.z))):
            xyz.columnconfigure(col, weight=1, uniform="xyz")
            cell = ttk.Frame(xyz, style="Panel.TFrame")
            cell.grid(row=0, column=col, sticky="ew", padx=3)
            ttk.Label(cell, text=label, style="Muted.TLabel").pack(anchor="w")
            ttk.Entry(cell, textvariable=var).pack(fill="x", pady=(4, 0))
        destination_buttons = ttk.Frame(destination, style="Panel.TFrame")
        destination_buttons.pack(fill="x", pady=(8, 0))
        destination_buttons.columnconfigure(0, weight=1)
        destination_buttons.columnconfigure(1, weight=1)
        ttk.Button(destination_buttons, text="MOVE TO XYZ", style="Accent.TButton",
                   command=lambda: self.coordinate_command("move_to")).grid(
            row=0, column=0, sticky="ew", padx=(3, 4))
        ttk.Button(destination_buttons, text="LOOK AT XYZ",
                   command=lambda: self.coordinate_command("look_at")).grid(
            row=0, column=1, sticky="ew", padx=(4, 3))

        follow = self._section(parent, "Player tracking")
        ttk.Label(follow, text="Player name (leave empty for first online player)",
                  style="Muted.TLabel").pack(anchor="w")
        follow_row = ttk.Frame(follow, style="Panel.TFrame")
        follow_row.pack(fill="x", pady=(7, 0))
        ttk.Entry(follow_row, textvariable=self.auto_follow).pack(
            side="left", fill="x", expand=True, padx=(0, 8))
        ttk.Button(follow_row, text="FOLLOW",
                   command=lambda: self.send("follow", {
                       "player": self.auto_follow.get()
                   })).pack(side="right")

        chat = self._section(parent, "Companion speech")
        ttk.Label(chat, text="Broadcast a message as MCCP in game chat",
                  style="Muted.TLabel").pack(anchor="w")
        speech = ttk.Frame(chat, style="Panel.TFrame")
        speech.pack(fill="x", pady=(7, 0))
        entry = ttk.Entry(speech, textvariable=self.message)
        entry.pack(side="left", fill="x", expand=True, padx=(0, 8))
        entry.bind("<Return>", lambda event: self.send(
            "say", {"text": self.message.get()[:240]}))
        ttk.Button(speech, text="SAY", style="Accent.TButton", command=lambda: self.send(
            "say", {"text": self.message.get()[:240]})).pack(side="right")
        ttk.Label(parent, text="Commands use the same approved-action API as the future agent.",
                  style="Muted.TLabel", font=("Segoe UI", 8), wraplength=300,
                  justify="left").pack(anchor="w", pady=(0, 9))

    def _build_inspect(self, parent):
        parent.columnconfigure(0, weight=1)
        parent.rowconfigure(0, weight=1)
        inner = ttk.Notebook(parent)
        inner.grid(row=0, column=0, sticky="nsew")
        self.views = {}
        for name in ("Visible Blocks", "Entities", "Inventory", "Raw Telemetry"):
            frame = ttk.Frame(inner, style="Panel.TFrame", padding=6)
            inner.add(frame, text=name)
            view = ScrolledText(frame, bg=PALETTE["field"], fg=PALETTE["text"],
                                insertbackground=PALETTE["text"], relief="flat",
                                borderwidth=0, font=("Consolas", 9), wrap="none")
            view.pack(fill="both", expand=True)
            self.views[name] = view

    def _build_activity(self, parent):
        parent.columnconfigure(0, weight=1)
        parent.rowconfigure(1, weight=1)
        ttk.Label(parent, text="Command history · connection errors",
                  style="Muted.TLabel").grid(row=0, column=0, sticky="ew",
                                             padx=10, pady=(12, 5))
        self.feedback = ScrolledText(
            parent, bg=PALETTE["field"], fg=PALETTE["text"],
            insertbackground=PALETTE["text"], relief="flat",
            borderwidth=0, font=("Consolas", 9), wrap="word")
        self.feedback.grid(row=1, column=0, sticky="nsew", padx=10, pady=(0, 10))
        self.feedback.insert("end", "MCCP COMMAND JOURNAL\n"
                             "Commands are proposed and approved before delivery.\n"
                             "A queued command is not proof of game execution.\n")
        self.feedback.configure(state="disabled")

    def _footer(self):
        footer = ttk.Frame(self.root, padding=(18, 0, 18, 8))
        footer.grid(row=3, column=0, sticky="ew")
        ttk.Label(footer, text="LOCAL API  /  127.0.0.1:8765",
                  style="Caption.TLabel", font=("Consolas", 9)).pack(side="left")
        ttk.Label(footer, text="COMPANION WORKBENCH   ·   " + APP_VERSION,
                  style="Caption.TLabel", font=("Consolas", 9)).pack(side="right")

    def _camera_placeholder(self):
        self.camera_canvas.delete("all")
        width = max(self.camera_canvas.winfo_width(), 320)
        height = max(self.camera_canvas.winfo_height(), 200)
        self.camera_canvas.create_text(
            width // 2, height // 2 - 10, text="NO CAMERA SIGNAL",
            fill=PALETTE["muted"], font=("Consolas", 14, "bold"), tags="placeholder"
        )
        self.camera_canvas.create_text(
            width // 2, height // 2 + 16, text="Waiting for companion preview",
            fill=PALETTE["border"], font=("Segoe UI", 10), tags="placeholder"
        )

    def _canvas_resized(self, event=None):
        if self.raw_image is None:
            self._camera_placeholder()
        else:
            self._draw_camera()

    def _draw_camera(self):
        if self.raw_image is None:
            return
        width = max(1, self.camera_canvas.winfo_width() - 20)
        height = max(1, self.camera_canvas.winfo_height() - 20)
        zoom = camera_scale(self.raw_image.width(), self.raw_image.height(), width, height)
        self.image = self.raw_image.zoom(zoom, zoom)
        self.camera_canvas.delete("all")
        self.camera_canvas.create_image(
            self.camera_canvas.winfo_width() // 2,
            self.camera_canvas.winfo_height() // 2,
            image=self.image, anchor="center"
        )
        self.camera_canvas.create_rectangle(
            0, 0, self.camera_canvas.winfo_width(), 3,
            fill=PALETTE["accent"], outline=""
        )

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
                # Python clears exception variables at the end of except blocks.
                # Capture text now or the scheduled callback raises NameError and
                # permanently kills telemetry refresh (especially hard to see in
                # a --windowed PyInstaller build).
                error_text = str(error)
                self.root.after(0, lambda msg=error_text: after(None, msg))
        threading.Thread(target=thread, daemon=True).start()

    def apply_preview(self):
        try:
            width, height = (int(p.strip()) for p in self.preview_width.get().split("×"))
            backend = self.preview_backend.get()
            fps = int(self.preview_fps.get())
        except (ValueError, TypeError):
            messagebox.showerror("Preview", "Select a valid resolution and frame rate.")
            return
        self.background(
            lambda: api("POST", "/api/v1/game/preview/config",
                        {"backend": backend, "width": width, "height": height, "fps": fps},
                        timeout=3),
            lambda result, error: self.log(
                "Preview settings failed: " + error if error else
                "Preview configured: %s %dx%d @ %d FPS" % (backend, width, height, fps))
        )

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
            health = api("GET", "/health", timeout=2)
            version = health.get("version", "unknown")
            if version != APP_VERSION:
                raise RuntimeError(
                    "BRAIN VERSION MISMATCH: running " + str(version) +
                    ", viewer " + APP_VERSION +
                    ". Update MCCP.exe and click Restart Brain (or stop the "
                    "leftover MCCP.exe --daemon process)."
                )
            state = api("GET", "/api/v1/game/state", timeout=3)
            config = api("GET", "/api/v1/state", timeout=3)
            return state, config
        def after(value, error):
            self.busy = False
            try:
                if error:
                    self.status.set("BRIDGE ERROR / " + error[:125])
                    self.frame_kind.configure(text="Telemetry unavailable; see control log for details")
                    if self.last_refresh_error != error:
                        self.log("Telemetry/API error: " + error)
                    self.last_refresh_error = error
                else:
                    if self.last_refresh_error:
                        self.log("Telemetry/API connection restored.")
                    self.last_refresh_error = None
                    state, config = value
                    self.policy.set(bool(config.get("config", {}).get("allow_world_actions", False)))
                    self.apply_state(state)
            except Exception as exc:
                message = str(exc)
                self.status.set("VIEWER ERROR / " + message[:110])
                if self.last_refresh_error != message:
                    self.log("Viewer refresh failed: " + message)
                self.last_refresh_error = message
            finally:
                # Always schedule again, including unexpected decoding/UI errors.
                self.root.after(int(POLL_SECONDS * 1000), self.refresh)
        self.background(work, after)

    def apply_state(self, state):
        if not isinstance(state.get("pose"), dict):
            self.status.set("BRAIN CONNECTED / Waiting for companion telemetry")
            self.frame_kind.configure(text="No telemetry yet. Check that Minecraft world and mod are running.")
            return
        captured_ms = state.get("timestamp_ms")
        if isinstance(captured_ms, (int, float)) and time.time() * 1000 - captured_ms > 10000:
            self.status.set("STALE TELEMETRY / companion " + str(state.get("companion_id", ""))[:8])
        else:
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
        frame_kind = state.get("frame_kind", "none")
        kind_label = {
            "semantic_raycast_png": "CPU RAYCAST",
            "gpu_voxel_shader_png": "GPU VOXEL SHADER",
            "unavailable": "NO VIDEO",
        }.get(frame_kind, frame_kind.upper())
        self.frame_kind.configure(text=kind_label)
        cfg = state.get("preview_config") or {}
        if cfg and not self.preview_loaded:
            self.preview_loaded = True
            self.preview_backend.set(cfg.get("backend", "cpu"))
            self.preview_width.set("%d × %d" % (cfg.get("width", 64), cfg.get("height", 36)))
            self.preview_fps.set(str(cfg.get("fps", 2)))
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
        # GPU images can arrive independently between slower world-scan snapshots.
        frame = state.get("frame_png_base64")
        frame_tick = state.get("frame_timestamp_ms", stamp)
        if frame and (frame_tick != self.frame_tick or frame != self.last_frame):
            try:
                self.raw_image = tk.PhotoImage(data=frame, format="png")
                self._draw_camera()
                self.last_frame = frame
                self.frame_tick = frame_tick
            except tk.TclError as error:
                self.frame_kind.configure(text="PNG ERROR: " + str(error)[:80])

    def run(self):
        self.root.mainloop()


if __name__ == "__main__":
    Viewer().run()
