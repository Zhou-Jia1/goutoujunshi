"""Regression tests using only synthetic data and an isolated memory directory."""

import argparse
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "memory_store.py"
# The packaged scripts directory must never contain generated runtime artifacts.
sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("memory_store", SCRIPT)
memory_store = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(memory_store)


class MemoryStoreTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.environment = mock.patch.dict(
            os.environ, {"GOUTOUJUNSHI_MEMORY_DIR": self.directory.name,
                         "PYTHONIOENCODING": "utf-8"}
        )
        self.environment.start()
        self.addCleanup(self.environment.stop)
        real_connect = memory_store.connect

        def tracked_connect(*args, **kwargs):
            conn = real_connect(*args, **kwargs)
            self.addCleanup(conn.close)
            return conn

        self.connections = mock.patch.object(memory_store, "connect", side_effect=tracked_connect)
        self.connections.start()
        self.addCleanup(self.connections.stop)
        self.cli("enable", "--confirm")

    def cli(self, *arguments, error=None):
        result = subprocess.run(
            [sys.executable, str(SCRIPT), *arguments], capture_output=True,
            encoding="utf-8", env=os.environ.copy(), check=False,
        )
        self.assertEqual(result.returncode, 1 if error else 0, result.stdout + result.stderr)
        payload = json.loads(result.stdout)
        if error:
            self.assertEqual(payload["error"]["code"], error)
        return payload

    def delta(self, value="synthetic-v1", **changes):
        return {"scope": "object", "subject_id": "synthetic-contact",
                "field": "stage", "value": value, "source_type": "user_report",
                **changes}

    def apply(self, value="synthetic-v1", **changes):
        return self.cli("apply", "--json", json.dumps(self.delta(value, **changes)))

    def snapshot(self):
        with contextlib.closing(sqlite3.connect(Path(self.directory.name) / "memory.sqlite3")) as conn:
            return tuple(conn.execute("SELECT rowid, * FROM " + table + " ORDER BY rowid").fetchall()
                         for table in ("memories", "operations", "settings"))

    def assert_conflict(self, op_id):
        before = self.snapshot()
        self.cli("undo", "--op-id", op_id, error="UNDO_CONFLICT")
        self.assertEqual(self.snapshot(), before)

    def test_older_update_conflicts_and_preserves_v2(self):
        first = self.apply()
        second = self.apply("synthetic-v2")
        self.assert_conflict(first["op_id"])
        self.assertEqual(self.cli("show")["memories"], [second["memory"]])

    def test_valid_dates_and_datetimes(self):
        for value in (None, "", "2026-10-28", "2026-10-28T20:00:00+08:00",
                      "2024-02-29", "2024-02-29T12:34:56Z", "2026-10-28T20:00:00",
                      "2026-10-28T20:00:00.123-07:00"):
            with self.subTest(value=value):
                result = self.apply(occurred_at=value)
                self.assertEqual(result["memory"]["occurred_at"], value or None)

    def test_invalid_dates_and_types_leave_store_unchanged(self):
        for value in ("not-a-date", "2026-02-29", "2024-02-30", "2026-13-01",
                      "2026-10-28T25:00:00", "2026-10-28garbage",
                      "2026-10-28T12:00:00+00:60", "2026-10-28T12:00:00+24:00",
                      "2026-10-28T12:00:00+08:99", "2026-10-28/12:00:00",
                      123, True, [], {}):
            with self.subTest(value=value):
                before = self.snapshot()
                self.cli("apply", "--json", json.dumps(self.delta(occurred_at=value)),
                         error="INVALID_DATE")
                self.assertEqual(self.snapshot(), before)

    def test_paused_apply_is_rejected(self):
        self.cli("pause")
        before = self.snapshot()
        self.cli("apply", "--json", json.dumps(self.delta()), error="MEMORY_PAUSED")
        self.assertEqual(self.snapshot(), before)

    def test_latest_undo_restores_previous_then_removes_creation(self):
        first = self.apply()
        second = self.apply("synthetic-v2")
        self.assertEqual(self.cli("undo")["undone_op_id"], second["op_id"])
        self.assertEqual(self.cli("show")["memories"], [first["memory"]])
        self.cli("undo")
        self.assertEqual(self.cli("show")["count"], 0)

    def test_unrelated_older_operation_can_be_undone(self):
        first = self.apply()
        second = self.apply("synthetic-unrelated", field="other-field")
        self.cli("undo", "--op-id", first["op_id"])
        self.assertEqual(self.cli("show")["memories"], [second["memory"]])

    def test_events_with_distinct_dates_are_unrelated(self):
        first = self.apply(scope="event", occurred_at="2026-10-27")
        second = self.apply("synthetic-next-event", scope="event", occurred_at="2026-10-28")
        self.cli("undo", "--op-id", first["op_id"])
        self.assertEqual(self.cli("show")["memories"], [second["memory"]])

    def test_later_related_history_conflicts_even_if_snapshot_matches(self):
        first = self.apply()
        self.apply("synthetic-v2")
        self.apply()
        # Match even timestamps, proving a snapshot-only check is insufficient.
        with memory_store.connect() as conn:
            memory_store.restore_rows(conn, [first["memory"]])
        self.assert_conflict(first["op_id"])

    def test_concurrent_writer_commits_before_undo_selects_and_checks(self):
        first = self.apply()
        conn = memory_store.connect()
        conn.execute("BEGIN IMMEDIATE")
        updated = {**first["memory"], "value": "synthetic-concurrent-v2"}
        memory_store.restore_rows(conn, [updated])
        conn.execute(
            "INSERT INTO operations VALUES (?, ?, 'apply', ?, ?)",
            ("synthetic-concurrent-operation", updated["updated_at"],
             json.dumps([first["memory"]]), json.dumps([updated])),
        )
        process = subprocess.Popen(
            [sys.executable, str(SCRIPT), "undo", "--op-id", first["op_id"]],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, encoding="utf-8",
            env=os.environ.copy(),
        )
        try:
            with self.assertRaises(subprocess.TimeoutExpired):
                process.wait(timeout=0.2)
            conn.commit()
            stdout, stderr = process.communicate(timeout=10)
            self.assertEqual(process.returncode, 1, stdout + stderr)
            self.assertEqual(json.loads(stdout)["error"]["code"], "UNDO_CONFLICT")
            self.assertEqual(self.cli("show")["memories"], [updated])
        finally:
            conn.rollback()
            if process.poll() is None:
                process.kill()
            process.communicate()

    def test_equal_timestamps_use_insertion_order_not_op_id(self):
        first = self.apply()
        second = self.apply("synthetic-v2")
        with memory_store.connect() as conn:
            conn.execute("UPDATE operations SET created_at = '2026-01-01T00:00:00+00:00'")
            conn.execute("UPDATE operations SET op_id = 'z-first' WHERE op_id = ?", (first["op_id"],))
            conn.execute("UPDATE operations SET op_id = 'a-second' WHERE op_id = ?", (second["op_id"],))
        self.assert_conflict("z-first")
        self.assertEqual(self.cli("undo")["undone_op_id"], "a-second")

    def test_undo_locks_before_selecting_operation(self):
        self.apply()
        statements = []
        real_connect = memory_store.connect

        def traced_connect():
            conn = real_connect()
            conn.set_trace_callback(statements.append)
            return conn

        with mock.patch.object(memory_store, "connect", side_effect=traced_connect):
            with contextlib.redirect_stdout(io.StringIO()):
                memory_store.command_undo(argparse.Namespace(op_id=None))
        begin = next(i for i, sql in enumerate(statements) if sql == "BEGIN IMMEDIATE")
        selected = next(i for i, sql in enumerate(statements) if "FROM operations" in sql)
        self.assertLess(begin, selected)

    def test_current_snapshot_drift_conflicts_without_later_history(self):
        for mutation in ("UPDATE memories SET value = 'synthetic-external'",
                         "DELETE FROM memories"):
            with self.subTest(mutation=mutation):
                operation = self.apply()
                with memory_store.connect() as conn:
                    conn.execute(mutation)
                self.assert_conflict(operation["op_id"])
                with memory_store.connect() as conn:
                    conn.execute("DELETE FROM operations")
                    conn.execute("DELETE FROM memories")

    def seed_pruning(self):
        # Low quota keeps the regression small while exercising the real prune path.
        with mock.patch.dict(memory_store.SCOPE_LIMITS, {"hypothesis": 2}):
            with contextlib.redirect_stdout(io.StringIO()):
                results = []
                for index in range(3):
                    args = argparse.Namespace(file=None, json=json.dumps(self.delta(
                        scope="hypothesis", field=f"synthetic-{index}")))
                    memory_store.command_apply(args)
                    results.append(self.snapshot())
        with memory_store.connect() as conn:
            operation = conn.execute("SELECT * FROM operations ORDER BY rowid DESC LIMIT 1").fetchone()
        return operation["op_id"], json.loads(operation["before_json"])[0], results[1][0]

    def test_latest_undo_restores_pruned_rows(self):
        operation, pruned, previous = self.seed_pruning()
        self.assertNotIn(pruned["id"], [r["id"] for r in self.cli("show")["memories"]])
        self.cli("undo", "--op-id", operation)
        self.assertEqual({r[1:] for r in self.snapshot()[0]}, {r[1:] for r in previous})

    def test_recreated_pruned_logical_record_conflicts(self):
        operation, pruned, _ = self.seed_pruning()
        self.apply("synthetic-recreated", scope=pruned["scope"], field=pruned["field"])
        self.assert_conflict(operation)

    def test_pruned_snapshot_reappearing_without_history_conflicts(self):
        operation, pruned, _ = self.seed_pruning()
        with memory_store.connect() as conn:
            memory_store.restore_rows(conn, [pruned])
        self.assert_conflict(operation)

    def test_pruned_replacement_without_history_conflicts(self):
        operation, pruned, _ = self.seed_pruning()
        with memory_store.connect() as conn:
            memory_store.restore_rows(conn, [{**pruned, "id": "synthetic-new-id"}])
        self.assert_conflict(operation)

    def test_updated_row_pruned_by_clock_rollback_restores_original_snapshot(self):
        self.seed_pruning()
        before = self.cli("show")["memories"]
        target = before[0]
        with mock.patch.dict(memory_store.SCOPE_LIMITS, {"hypothesis": 1}):
            with mock.patch.object(memory_store, "now_iso", return_value="2000-01-01T00:00:00+00:00"):
                with contextlib.redirect_stdout(io.StringIO()):
                    memory_store.command_apply(argparse.Namespace(file=None, json=json.dumps(
                        self.delta("synthetic-pruned-update", scope="hypothesis", field=target["field"]))))
        self.cli("undo")
        self.assertEqual({r["id"]: r for r in self.cli("show")["memories"]},
                         {r["id"]: r for r in before})


if __name__ == "__main__":
    unittest.main()
