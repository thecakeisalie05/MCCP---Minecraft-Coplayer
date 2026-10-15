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


class ViewerTests(unittest.TestCase):
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
        self.assertEqual(v.root.actions.get(timeout=2)[0], 1000)
        self.assertIn("version mismatch", logs[0].lower())

    def test_waiting_state_is_not_mistaken_for_offline(self):
        v = viewer.Viewer.__new__(viewer.Viewer)
        v.status = FakeVar()
        v.frame_kind = FakeVar()
        v.apply_state({"protocol": "mccp-game-v1", "connected": False})
        self.assertIn("BRAIN CONNECTED", v.status.value)


if __name__ == "__main__":
    unittest.main()
