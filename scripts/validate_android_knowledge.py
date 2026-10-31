"""Standard-library T03 validator and deterministic reference selector.

Selection: safety (mandatory), keyword rules descending priority (ties retain JSON
order), goal rules, scene defaults. First occurrence wins; retain whole documents
that fit the remaining Unicode code-point budget, at most three. Never truncate a
reference. A mandatory safety reference that cannot fit, or an empty result, fails
closed with ValueError. max_chars counts reference contents only (not prompts).
Content is a project-owner review draft, not approval to release.
"""
import sys
sys.dont_write_bytecode = True
import json
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "android/core/knowledge/src/main/assets/app-prompt"
SCENES = ("work", "intimate", "family", "social", "mediation")
RELATIONS = dict(zip(
    "BOSS COLLEAGUE SUBORDINATE CLIENT CRUSH PARTNER EX PARENT IN_LAW RELATIVE CHILD SIBLING FRIEND ACQUAINTANCE GROUP MEDIATION".split(),
    ["work"] * 4 + ["intimate"] * 3 + ["family"] * 5 + ["social"] * 3 + ["mediation"],
))
GOALS = "REPLY REPORT ASK_HELP DECLINE APOLOGIZE CLARIFY COMFORT ADVANCE INVITE BOUNDARY ICEBREAK REPAIR MEDIATE CLOSE".split()


def load_routes():
    return json.loads((ASSETS / "routes.json").read_text(encoding="utf-8"))


def read_reference(reference):
    """Only existing canonical POSIX references/**.md paths are allowed."""
    if not isinstance(reference, str) or "\\" in reference or ":" in reference:
        raise ValueError("Invalid reference path")
    path = PurePosixPath(reference)
    if (path.is_absolute() or ".." in path.parts or len(path.parts) < 3
            or path.parts[0] != "references" or path.suffix != ".md"
            or str(path) != reference):
        raise ValueError("Reference path must stay within references/")
    resolved = (ROOT / reference).resolve()
    if not resolved.is_relative_to((ROOT / "references").resolve()) or not resolved.is_file():
        raise ValueError("Reference is missing or escapes references/")
    return resolved.read_text(encoding="utf-8")


def select_references(relation_type, goal, text, max_chars=24000):
    routes = load_routes()
    if relation_type not in RELATIONS or goal not in GOALS:
        raise ValueError("Unknown relation type or goal")
    if not isinstance(max_chars, int) or isinstance(max_chars, bool) or max_chars <= 0:
        raise ValueError("max_chars must be positive")
    lowered = text.lower()
    mandatory = routes["safetyReferences"] if any(w.lower() in lowered for w in routes["safetyKeywords"]) else []
    candidates = list(mandatory)
    for rule in sorted(routes["keywordRules"], key=lambda rule: -rule["priority"]):
        if any(word.lower() in lowered for word in rule["keywords"]):
            candidates.extend(rule["references"])
    candidates.extend(routes["goalRules"][goal])
    candidates.extend(routes["defaults"][routes["relationTypes"][relation_type]])
    selected, seen, used = [], set(), 0
    for reference in candidates:
        if reference in seen:
            continue
        seen.add(reference)
        content = read_reference(reference)
        fits = len(selected) < 3 and used + len(content) <= max_chars
        if reference in mandatory and not fits:
            raise ValueError("Safety references exceed budget; do not call the model")
        if fits:
            selected.append(reference)
            used += len(content)
    if not selected:
        raise ValueError("No whole reference fits budget; do not call the model")
    return selected


def validate():
    routes = load_routes()
    assert routes["version"] == 1
    assert routes["relationTypes"] == RELATIONS, "Relation type contract drift"
    assert routes["goals"] == GOALS, "Goal contract drift"
    assert set(routes["defaults"]) == set(SCENES)
    assert set(routes["goalRules"]) == set(GOALS)
    assert routes["status"] == "负责人待审稿"
    assert routes["safetyKeywords"] and routes["safetyReferences"]
    groups = list(routes["defaults"].values()) + list(routes["goalRules"].values()) + [routes["safetyReferences"]]
    for rule in routes["keywordRules"]:
        assert rule["keywords"] and all(isinstance(w, str) and w.strip() for w in rule["keywords"])
        assert type(rule["priority"]) is int
        groups.append(rule["references"])
    for group in groups:
        assert 1 <= len(group) <= 3 and len(group) == len(set(group))
        for reference in group:
            read_reference(reference)
    assert any("/17-" in ref for ref in routes["safetyReferences"])
    scene_lengths = {}
    for scene in SCENES:
        content = (ASSETS / "scenes" / (scene + ".md")).read_text(encoding="utf-8")
        scene_lengths[scene] = len(content)
        assert len(content) <= 2500 and "负责人待审稿" in content
        assert all(goal in content for goal in GOALS)
        if scene != "intimate":
            assert "不问 MBTI" in content and "不默认约会推进" in content
    count, largest = 0, 0
    all_keywords = " ".join(word for rule in routes["keywordRules"] for word in rule["keywords"])
    for relation in RELATIONS:
        for goal in GOALS:
            for text in ["", all_keywords, all_keywords + " " + routes["safetyKeywords"][0]]:
                result = select_references(relation, goal, text)
                assert 1 <= len(result) <= 3 and len(result) == len(set(result))
                size = sum(len(read_reference(ref)) for ref in result)
                assert size <= 24000
                largest = max(largest, size)
                if routes["safetyKeywords"][0] in text:
                    assert all(ref in result for ref in routes["safetyReferences"])
            count += 1
    return {"status": "负责人待审稿", "relations": len(RELATIONS), "goals": len(GOALS),
            "combinations": count, "selectionChecks": count * 3,
            "keywordRules": len(routes["keywordRules"]), "references": len({ref for group in groups for ref in group}),
            "maxReferenceChars": largest, "budget": 24000, "sceneChars": scene_lengths}


if __name__ == "__main__":
    print(json.dumps(validate(), ensure_ascii=True, indent=2))
