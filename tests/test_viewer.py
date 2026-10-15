"""Viewer headless regression tests. No Tk window or Minecraft instance required."""
import queue
import threading
import unittest
from unittest import mock

import viewer


class FakeRoot:
    def __init__(self):
        self.actions = queue.Queue()

    def after(self, ms, callback):
        self.actions.put((ms, callback))


class FakeVar:
    def __init__(self):
        self.value = None
    def set(self, value):
        self.value = value

    def configure(self, **kwargs):
        self.value = kwargs.get("text", self.value)


class ViewerTests(unittest.TestCase):
    def test_camera_scale_preserves_pixels_and_small_windows(self):
        self.assertEqual(viewer.camera_scale(64, 36, 640, 360), 10)
        self.assertEqual(viewer.camera_scale(80, 45, 500, 220), 4)
        self.assertEqual(viewer.camera_scale(128, 72, 60, 45), 1)
        self.assertEqual(viewer.camera_scale(0, 36, 640, 360), 1)

    def test_world_commands_preserved_during_layout_redesign(self):
        expected = {
            "spawn", "despawn", "move_to", "look_at", "follow",
            "move_relative", "turn_by", "jump"
        }
        self.assertTrue(expected.issubset(viewer.WORLD_ACTIONS))

    def test_follow_continuous_is_gated_but_route_probe_and_stop_are_safe(self):
        self.assertIn("follow_continuous", viewer.WORLD_ACTIONS)
        self.assertNotIn("check_route", viewer.WORLD_ACTIONS)
        self.assertNotIn("stop_follow", viewer.WORLD_ACTIONS)

    def test_background_error_callback_captures_exception_text(self):
        v = viewer.Viewer.__new__(viewer.Viewer)
        v.root = FakeRoot()
        observed = []
        v.background(lambda: (_ for _ in ()).throw(
            ValueError("simulated API 404")), lambda result, error:
            observed.append((result, error)))
        _, callback = v.root.actions.get(timeout=4)
        callback()
        self.assertEqual(observed, [(None, "simulated API 404")])

    def test_background_success(self):
        v = viewer.Viewer.__new__(viewer.Viewer)
        v.root = FakeRoot()
        seen = []
        v.background(lambda: {"ok": True}, lambda result, error:
                     seen.append((result, error)))
        _, callback = v.root.actions.get(timeout=4)
        callback()
        self.assertEqual(seen, [({"ok": True}, None)])

    def test_refresh_mismatch_is_visible_and_retries(self):
        v = viewer.Viewer.__new__(viewer.Viewer)
        v.root = FakeRoot()
        v.status = FakeVar()
        v.frame_kind = FakeVar()
        v.policy = FakeVar()
        v.busy = False
        v.last_refresh_error = None
        logs = []
        v.log = logs.append
        with mock.patch.object(viewer, "api", return_value={
            "ok": True, "service": "mccp-brain", "version": "0.1.5"
        }):
            v.refresh()
            _, callback = v.root.actions.get(timeout=4)
            callback()
        self.assertIn("VERSION MISMATCH", v.status.value)
        self.assertFalse(v.busy)
        self.assertEqual(v.root.actions.get(timeout=2)[0], int(viewer.POLL_SECONDS * 1000))
        self.assertIn("version mismatch", logs[0].lower())

    def test_waiting_state_is_not_mistaken_for_offline(self):
        v = viewer.Viewer.__new__(viewer.Viewer)
        v.status = FakeVar()
        v.frame_kind = FakeVar()
        v.apply_state({"protocol": "mccp-game-v1", "connected": False})
        self.assertIn("BRAIN CONNECTED", v.status.value)


if __name__ == "__main__":
    unittest.main()
