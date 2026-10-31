#!/usr/bin/env python3
"""Validate synthetic evaluation fixtures with JSON Schema; never call a model."""
from __future__ import annotations

import json
from collections import Counter
from pathlib import Path

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]
GROUPS = {
    "BOSS": "work", "COLLEAGUE": "work", "SUBORDINATE": "work", "CLIENT": "work",
    "CRUSH": "intimate", "PARTNER": "intimate", "EX": "intimate",
    "PARENT": "family", "IN_LAW": "family", "RELATIVE": "family", "CHILD": "family", "SIBLING": "family",
    "FRIEND": "social", "ACQUAINTANCE": "social", "GROUP": "social", "MEDIATION": "mediation",
}
REQUIRED_TAGS = {"speaker_reversal", "contact_isolation", "prompt_injection", "explicit_refusal", "ambiguous_instruction", "unreasonable_request", "family_pressure", "in_law_mediation", "blame_shifting", "same_gender", "high_emotion", "self_harm", "violence", "stalking", "style_alignment"}


def validate() -> tuple[int, dict[str, int]]:
    schema = json.loads((ROOT / "evals/schema.json").read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema)
    ids: set[str] = set()
    counts: Counter[str] = Counter()
    tags: set[str] = set()
    for path in sorted((ROOT / "evals/cases").glob("*.json")):
        case = json.loads(path.read_text(encoding="utf-8"))
        errors = list(validator.iter_errors(case))
        if errors:
            raise ValueError(f"{path.name}: {errors[0].message}")
        if case["id"] in ids or path.stem != case["id"]:
            raise ValueError(f"duplicate or mismatched ID: {path.name}")
        ids.add(case["id"])
        line_ids = [line["id"] for line in case["chat"]]
        if len(set(line_ids)) != len(line_ids):
            raise ValueError(f"duplicate line IDs: {path.name}")
        counts[GROUPS[case["relationType"]]] += 1
        tags.update(case["tags"])
    if len(ids) < 50 or any(counts[group] < 8 for group in {"work", "intimate", "family", "social", "mediation"}):
        raise ValueError(f"insufficient fixture coverage: {dict(counts)}")
    if REQUIRED_TAGS - tags:
        raise ValueError(f"missing required tags: {sorted(REQUIRED_TAGS - tags)}")
    return len(ids), dict(counts)


if __name__ == "__main__":
    count, groups = validate()
    print(f"Evaluation schema passed: {count} synthetic cases, {groups}")
