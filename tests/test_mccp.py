"""Regression and API tests for MCCP; run with python -m unittest discover -s tests."""
import io
import json
import os
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest import mock

import mccp


class BrainTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.originals = (mccp.DATA, mccp.CONFIG_FILE, mccp.TOKEN_FILE)
        mccp.DATA = Path(self.tmp.name)
        mccp.CONFIG_FILE = mccp.DATA / "brain.json"
        mccp.TOKEN_FILE = mccp.DATA / "api-token"
        self.brain = mccp.Brain()

    def tearDown(self):
        mccp.DATA, mccp.CONFIG_FILE, mccp.TOKEN_FILE = self.originals
        self.tmp.cleanup()

    def test_save_and_restore(self):
        self.brain.update_config({"model": "test-model", "autonomy": 75})
        self.brain.add_goal("Inspect LV factory")
        other = mccp.Brain()
        self.assertEqual(other.config["model"], "test-model")
        self.assertEqual(other.config["autonomy"], 75)
        self.assertEqual(other.goals[0]["text"], "Inspect LV factory")

    def test_toggle_and_validation(self):
        self.brain.update_config({"modules": {"memory": False}, "autonomy": 0})
        self.assertFalse(self.brain.config["modules"]["memory"])
        self.assertEqual(self.brain.config["autonomy"], 0)
        with self.assertRaises(ValueError):
            self.brain.update_config({"autonomy": -3})
        with self.assertRaises(ValueError):
            self.brain.update_config({"modules": {"unsafe": True}})
        with self.assertRaises(ValueError):
            self.brain.update_config({"ollama_url": "https://example.com"})
        with self.assertRaises(ValueError):
            self.brain.update_config({"unknown": 1})

    def test_observation_and_connection(self):
        result = self.brain.observe({"kind": "player_nearby", "payload": {"x": 10}})
        self.assertEqual(result["kind"], "player_nearby")
        self.assertTrue(self.brain.snapshot()["connected"])
        self.brain.update_config({"modules": {"perception": False}})
        self.assertEqual(self.brain.observe({"kind": "block"}).get("ignored"), "perception disabled")

    def test_action_approval_and_world_permissions(self):
        proposal = self.brain.propose("move_to", {"x": 2, "y": 64, "z": 3})
        with self.assertRaises(ValueError):
            self.brain.decide_proposal(proposal["id"], True)
        self.brain.update_config({"allow_world_actions": True})
        self.brain.decide_proposal(proposal["id"], True)
        self.assertEqual(len(self.brain.poll_commands()), 1)
        self.assertEqual(self.brain.poll_commands(), [])
        with self.assertRaises(ValueError):
            self.brain.decide_proposal(proposal["id"], True)
        with self.assertRaises(ValueError):
            self.brain.propose("shell_command", {"command": "rm -rf /"})

    def test_goals(self):
        goal = self.brain.add_goal("Explore the base")
        self.assertEqual(goal["status"], "pending")
        self.assertEqual(self.brain.set_goal(goal["id"], "done")["status"], "done")
        with self.assertRaises(ValueError):
            self.brain.set_goal(goal["id"], "flying")

    def test_persistent_memory_and_unique_ids(self):
        first = self.brain.add_goal("First goal")
        note = self.brain.add_memory("The base is in the overworld.")
        restored = mccp.Brain()
        self.assertEqual(restored.memory[0]["text"], note["text"])
        self.assertNotEqual(restored.add_goal("Second goal")["id"], first["id"])
        self.assertTrue(restored.remove_memory(note["id"])["removed"])
        with self.assertRaises(ValueError):
            restored.remove_memory(note["id"])

    def test_personality_is_editable(self):
        self.brain.update_config({"personality": "Curious but cautious."})
        self.assertEqual(mccp.Brain().config["personality"], "Curious but cautious.")
        with self.assertRaises(ValueError):
            self.brain.update_config({"personality": ""})

    def test_local_token(self):
        first = mccp.get_or_create_token()
        self.assertGreater(len(first), 30)
        self.assertEqual(first, mccp.get_or_create_token())

    def test_model_response_is_grounded_and_recorded(self):
        raw = io.BytesIO(json.dumps({"message": {"content": "I can inspect that once connected."}}).encode())
        with mock.patch("mccp.urllib.request.urlopen", return_value=raw):
            reply = self.brain.ollama("Are you able to inspect the machine?")
        self.assertIn("once connected", reply)
        self.assertEqual(len(self.brain.messages), 2)

    def test_autonomy_creates_reviewable_suggestion_only(self):
        self.brain.add_goal("Check the machine")
        self.brain.observe({"kind": "player_nearby"})
        with mock.patch.object(self.brain, "ollama", return_value="Check the LV machine."):
            self.brain.autonomous_tick()
        self.assertEqual(len(self.brain.proposals), 1)
        self.assertEqual(self.brain.proposals[0]["kind"], "say")
        self.assertEqual(self.brain.outbox, [])


class APITests(BrainTests):
    def setUp(self):
        super().setUp()
        self.token = "test-only-token"
        class BoundHandler(mccp.Handler):
            pass
        BoundHandler.brain = self.brain
        BoundHandler.token = self.token
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), BoundHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = "http://127.0.0.1:" + str(self.server.server_address[1])

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
        super().tearDown()

    def request(self, method, path, payload=None, token=True):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["X-MCCP-Token"] = self.token
        request = urllib.request.Request(
            self.url + path, method=method,
            data=json.dumps(payload).encode() if payload is not None else None,
            headers=headers
        )
        with urllib.request.urlopen(request, timeout=3) as response:
            return json.load(response)

    def test_health_is_public_but_state_is_not(self):
        self.assertTrue(self.request("GET", "/health", token=False)["ok"])
        with self.assertRaises(urllib.error.HTTPError) as error:
            self.request("GET", "/api/v1/state", token=False)
        self.assertEqual(error.exception.code, 401)

    def test_api_e2e_observe_settings_actions(self):
        self.request("POST", "/api/v1/config", {"allow_world_actions": True})
        self.request("POST", "/api/v1/observe", {"kind": "player_nearby", "payload": {"player": "Connor"}})
        proposal = self.request("POST", "/api/v1/actions/propose", {
            "kind": "follow", "arguments": {"player": "Connor"}
        })
        self.request("POST", "/api/v1/actions/review", {"id": proposal["id"], "approved": True})
        commands = self.request("POST", "/api/v1/commands/poll", {})["commands"]
        self.assertEqual(commands[0]["kind"], "follow")
        self.assertTrue(self.request("GET", "/api/v1/state")["connected"])

    def test_pause_resume(self):
        self.assertTrue(self.request("POST", "/api/v1/control", {"operation": "pause"})["paused"])
        self.assertFalse(self.request("POST", "/api/v1/control", {"operation": "resume"})["paused"])


if __name__ == "__main__":
    unittest.main()
