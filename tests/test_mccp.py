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

    def test_emergency_stop_rejects_unsent_actions(self):
        approved = self.brain.propose("say", {"text": "Hello"})
        self.brain.decide_proposal(approved["id"], True)
        pending = self.brain.propose("move_to", {"x": 1})
        result = self.brain.emergency_stop()
        self.assertTrue(result["paused"])
        self.assertEqual(result["discarded_commands"], 1)
        self.assertEqual(result["rejected_proposals"], 1)
        self.assertEqual(self.brain.poll_commands(), [])
        self.assertEqual(pending["status"], "rejected")
        another = self.brain.propose("say", {"text": "Not while paused"})
        with self.assertRaises(ValueError):
            self.brain.decide_proposal(another["id"], True)

    def test_autonomous_inference_cannot_undo_emergency_stop(self):
        self.brain.add_goal("Investigate the system")
        self.brain.observe({"kind": "player_nearby"})
        def interrupt(prompt):
            self.brain.emergency_stop()
            return "Don't say this."
        with mock.patch.object(self.brain, "ollama", side_effect=interrupt):
            self.brain.autonomous_tick()
        self.assertEqual(self.brain.proposals, [])

    def test_local_token(self):
        first = mccp.get_or_create_token()
        self.assertGreater(len(first), 30)
        self.assertEqual(first, mccp.get_or_create_token())

    def test_daemon_version_mismatch_detected(self):
        self.assertTrue(mccp.daemon_is_current({
            "ok": True, "service": "mccp-brain", "version": mccp.APP_VERSION
        }))
        self.assertFalse(mccp.daemon_is_current({
            "ok": True, "service": "mccp-brain", "version": "0.1.0"
        }))
        self.assertFalse(mccp.daemon_is_current({
            "ok": True, "service": "other-service", "version": mccp.APP_VERSION
        }))

    def test_start_daemon_does_not_spawn_again_for_old_brain(self):
        with mock.patch("mccp.daemon_health", return_value={
            "service": "mccp-brain", "version": "0.1.0"
        }), mock.patch("mccp.subprocess.Popen") as spawn:
            mccp.start_daemon()
        spawn.assert_not_called()

    def test_empty_ollama_response_diagnostic(self):
        raw = io.BytesIO(json.dumps({
            "model": "qwen3:4b", "message": {"content": ""}, "done_reason": "stop",
            "eval_count": 0
        }).encode())
        with mock.patch("mccp.urllib.request.urlopen", return_value=raw):
            with self.assertRaisesRegex(RuntimeError, "done_reason=stop"):
                self.brain.ollama("Hello")

    def test_visible_model_reply_strips_embedded_thinking(self):
        text = ("<think>Plan internally, do not show this.</think> "
                "Hello! I am ready to help.")
        self.assertEqual(mccp.visible_model_reply(text), "Hello! I am ready to help.")

    def test_visible_model_reply_handles_orphan_think_close(self):
        text = ("I should write a greeting.\n</think>\n"
                "Hi! Ready to explore GTE.")
        self.assertEqual(mccp.visible_model_reply(text), "Hi! Ready to explore GTE.")

    def test_visible_model_reply_discards_unclosed_thinking(self):
        self.assertEqual(mccp.visible_model_reply("<think>Still reasoning"), "")
        self.assertEqual(mccp.visible_model_reply("Hello. <think>private"), "Hello.")
        self.assertEqual(mccp.visible_model_reply(None), "")

    def test_ollama_does_not_log_thinking_as_chat(self):
        raw = io.BytesIO(json.dumps({"message": {
            "content": "<think>Reasoning should remain hidden</think> Hello!"
        }}).encode())
        with mock.patch("mccp.urllib.request.urlopen", return_value=raw):
            reply = self.brain.ollama("Hi")
        self.assertEqual(reply, "Hello!")
        self.assertEqual(self.brain.messages[-1]["text"], "Hello!")
        self.assertNotIn("Reasoning", str(self.brain.messages))

    def test_ollama_only_think_text_is_actionable_error(self):
        raw = io.BytesIO(json.dumps({"message": {
            "content": "<think>No final output</think>"
        }}).encode())
        with mock.patch("mccp.urllib.request.urlopen", return_value=raw):
            with self.assertRaisesRegex(RuntimeError, "only reasoning text"):
                self.brain.ollama("Hi")
        self.assertEqual(len(self.brain.messages), 0)

    def test_preview_presets_validation_and_persistence(self):
        self.assertEqual(self.brain.set_preview({
            "backend":"cpu", "width":64, "height":36, "fps":2
        })["fps"], 2)
        with self.assertRaises(ValueError):
            self.brain.set_preview({"width":80, "height":36})
        with self.assertRaises(ValueError):
            self.brain.set_preview({"fps":8})
        with self.assertRaises(ValueError):
            self.brain.set_preview({"backend":"none"})
        self.brain.set_preview({"backend":"gpu", "fps":8})
        self.assertEqual(mccp.Brain().config["preview"]["fps"], 8)

    def test_gpu_frame_requires_matching_companion_and_png(self):
        self.brain.set_preview({"backend":"gpu"})
        self.brain.ingest_game({"protocol":"mccp-game-v1",
            "companion_id":"avatar1", "pose":{"x":0,"y":70,"z":0}, "inventory":[]})
        import base64
        image = base64.b64encode(b"\x89PNG\r\n\x1a\n" + b"fakedata").decode()
        frame = {"protocol":"mccp-frame-v1","companion_id":"avatar1",
            "frame_kind":"gpu_voxel_shader_png", "frame_width":64,
            "frame_height":36, "frame_png_base64":image}
        self.assertTrue(self.brain.ingest_frame(frame)["accepted"])
        state = self.brain.current_game_state()
        self.assertEqual(state["frame_kind"], "gpu_voxel_shader_png")
        self.assertEqual(state["preview_config"]["fps"], 2)
        with self.assertRaises(ValueError):
            self.brain.ingest_frame({**frame,"companion_id":"wrong"})
        with self.assertRaises(ValueError):
            self.brain.ingest_frame({**frame,"frame_png_base64":"garbage"})

    def test_ingest_game_state_and_summary(self):
        report = {"protocol": "mccp-game-v1",
                  "pose": {"x": 10, "y": 70, "z": -4},
                  "stats": {"health": 20},
                  "blocks": [{"block": "minecraft:stone"}],
                  "entities": [], "inventory": [],
                  "frame_kind": "semantic_raycast_png", "frame_png_base64": "YWJj"}
        self.assertTrue(self.brain.ingest_game(report)["accepted"])
        self.assertEqual(self.brain.game_snapshot["pose"]["x"], 10)
        self.assertEqual(self.brain.observations[-1]["payload"]["blocks_visible"], 1)
        self.assertTrue(self.brain.snapshot()["game_connected"])
        self.assertNotIn("frame_png_base64", str(self.brain.observations[-1]))

    def test_ingest_game_rejects_oversized_or_bad_observations(self):
        with self.assertRaises(ValueError):
            self.brain.ingest_game({"protocol": "old", "pose": {"x": 1, "y": 2, "z": 3}})
        with self.assertRaises(ValueError):
            self.brain.ingest_game({"protocol": "mccp-game-v1", "pose": {"x": "bad", "y": 2, "z": 3}})
        with self.assertRaises(ValueError):
            self.brain.ingest_game({"protocol": "mccp-game-v1", "pose": {"x": 1, "y": 2, "z": 3},
                                    "blocks": [{}] * 1201})

    def test_model_response_is_grounded_and_recorded(self):
        raw = io.BytesIO(json.dumps({"message": {"content": "I can inspect that once connected."}}).encode())
        with mock.patch("mccp.urllib.request.urlopen", return_value=raw):
            reply = self.brain.ollama("Are you able to inspect the machine?")
        self.assertIn("once connected", reply)
        self.assertEqual(len(self.brain.messages), 2)

    def test_ollama_disables_thinking_for_qwen3(self):
        captured = {}
        def fake_urlopen(request, timeout):
            captured["request"] = json.loads(request.data)
            return io.BytesIO(json.dumps({
                "message": {"content": "I am ready to help."}, "done_reason": "stop"
            }).encode())
        with mock.patch("mccp.urllib.request.urlopen", side_effect=fake_urlopen):
            self.assertIn("ready", self.brain.ollama("Say hello"))
        body = captured["request"]
        self.assertIs(body["think"], False)
        self.assertNotIn("think", body["options"])
        self.assertGreaterEqual(body["options"]["num_predict"], 512)
        self.assertEqual(body["model"], "qwen3:4b")

    def test_only_thinking_returns_actionable_error(self):
        raw = io.BytesIO(json.dumps({
            "message": {"content": "", "thinking": "Reasoning without an answer"},
            "done_reason": "length"
        }).encode())
        with mock.patch("mccp.urllib.request.urlopen", return_value=raw):
            with self.assertRaisesRegex(RuntimeError, "generated reasoning but no final answer"):
                self.brain.ollama("Hello")
        self.assertFalse(self.brain.messages)

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

    def test_preview_settings_http(self):
        cfg = self.request("POST", "/api/v1/game/preview/config",
            {"backend":"gpu","width":80,"height":45,"fps":4})
        self.assertEqual(cfg["fps"], 4)
        self.assertEqual(self.request("GET", "/api/v1/game/preview/config")["width"], 80)

    def test_game_bridge_http_flow(self):
        telemetry = {
            "protocol": "mccp-game-v1", "pose": {"x": 4, "y": 65, "z": 8},
            "inventory": [], "blocks": [], "entities": [],
            "frame_kind": "semantic_raycast_png"
        }
        result = self.request("POST", "/api/v1/game/telemetry", telemetry)
        self.assertTrue(result["accepted"])
        state = self.request("GET", "/api/v1/game/state")
        self.assertEqual(state["pose"]["x"], 4)
        self.assertEqual(state["frame_kind"], "semantic_raycast_png")

    def test_api_emergency_stop(self):
        self.request("POST", "/api/v1/actions/propose", {
            "kind": "say", "arguments": {"text": "test"}
        })
        result = self.request("POST", "/api/v1/control", {"operation": "emergency_stop"})
        self.assertTrue(result["paused"])
        self.assertEqual(result["rejected_proposals"], 1)

    def test_pause_resume(self):
        self.assertTrue(self.request("POST", "/api/v1/control", {"operation": "pause"})["paused"])
        self.assertFalse(self.request("POST", "/api/v1/control", {"operation": "resume"})["paused"])


if __name__ == "__main__":
    unittest.main()
