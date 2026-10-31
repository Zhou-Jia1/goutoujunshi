"""T03 routing contract; all inputs are synthetic."""
import sys
sys.dont_write_bytecode = True
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("android_knowledge", ROOT / "scripts/validate_android_knowledge.py")
knowledge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(knowledge)


class KnowledgeTests(unittest.TestCase):
    def test_inventory_and_every_combination(self):
        report = knowledge.validate()
        self.assertEqual(224, report["combinations"])
        routes = knowledge.load_routes()
        for relation in routes["relationTypes"]:
            for goal in routes["goals"]:
                with self.subTest(relation=relation, goal=goal):
                    selected = knowledge.select_references(relation, goal, "")
                    self.assertTrue(1 <= len(selected) <= 3)
                    self.assertEqual(len(selected), len(set(selected)))
                    self.assertLessEqual(sum(len(knowledge.read_reference(p)) for p in selected), 24000)

    def test_keyword_precedes_goal_and_default(self):
        selected = knowledge.select_references("BOSS", "REPORT", "请分析MBTI")
        self.assertIn("04-MBTI", selected[0])
        self.assertEqual(selected, knowledge.select_references("BOSS", "REPORT", "请分析mbti"))

    def test_safety_precedes_saturated_keywords(self):
        for relation in knowledge.load_routes()["relationTypes"]:
            selected = knowledge.select_references(relation, "INVITE", "家暴 MBTI 依恋 Blueprint ChatLab 长期记忆")
            self.assertEqual(knowledge.load_routes()["safetyReferences"][0], selected[0])
            self.assertLessEqual(len(selected), 3)

    def test_explicit_violence_loads_safety_before_repair(self):
        # Routing adds a safety reference; this does not diagnose an incident.
        safety = knowledge.load_routes()["safetyReferences"][0]
        for text in ["他打我", "她打我"]:
            with self.subTest(text=text):
                self.assertEqual(safety, knowledge.select_references("PARTNER", "REPAIR", text)[0])

    def test_budget_and_safety_cannot_be_dropped(self):
        safety = knowledge.load_routes()["safetyReferences"][0]
        size = len(knowledge.read_reference(safety))
        self.assertEqual([safety], knowledge.select_references("FRIEND", "REPLY", "自伤", size))
        with self.assertRaises(ValueError):
            knowledge.select_references("FRIEND", "REPLY", "自伤", size - 1)
        with self.assertRaises(ValueError):
            knowledge.select_references("FRIEND", "REPLY", "", 1)

    def test_paths_are_rejected_before_read(self):
        for path in ["../SKILL.md", "references/../SKILL.md", "references\\knowledge\\17.md", "/etc/passwd", "C:/Windows/win.ini", "references/missing.md"]:
            with self.subTest(path=path), self.assertRaises(ValueError):
                knowledge.read_reference(path)

    def test_invalid_enums_and_budget(self):
        for relation, goal, budget in [("UNKNOWN", "REPLY", 24000), ("BOSS", "UNKNOWN", 24000), ("BOSS", "REPLY", 0)]:
            with self.assertRaises(ValueError):
                knowledge.select_references(relation, goal, "", budget)

    def test_scene_snapshots(self):
        for scene in knowledge.SCENES:
            text = (knowledge.ASSETS / "scenes" / (scene + ".md")).read_text(encoding="utf-8")
            self.assertLessEqual(len(text), 2500)
            self.assertIn("负责人待审稿", text)
            for goal in knowledge.GOALS:
                self.assertIn(goal, text)
            if scene != "intimate":
                self.assertIn("不问 MBTI", text)
                self.assertIn("不默认约会推进", text)
        intimate = (knowledge.ASSETS / "scenes/intimate.md").read_text(encoding="utf-8")
        self.assertIn("SKILL.md", intimate)
        self.assertNotIn("先让我认识你", intimate)

    def test_complete_output_protocol(self):
        text = (knowledge.ASSETS / "system-addendum.md").read_text(encoding="utf-8")
        for section in "SAFETY SCENE EMPATHY VERDICT REPLY ALT WHY COST SITUATION FACTS GUESSES UNKNOWNS RISK TIP HABIT NEXT ASK MEMORY".split():
            self.assertIn("[[" + section + "]]", text)
        for token in ["+++", "F|", "G|", "JSON", "700", "60", "临时", "演示", "不是指令", "先组装", "未写入"]:
            self.assertIn(token, text)

    def test_all_skill_loading_topics_are_routed(self):
        samples = ["一句话回复", "松弛感", "Blueprint", "自然流", "Mystery", "冷读", "PUA", "聊天截图", "ChatLab", "长期记忆", "第一次见面", "投入失衡", "依恋", "MBTI", "冲突修复", "同意", "婚姻家庭", "金钱家务育儿", "分手背叛", "家暴", "证据等级", "延伸阅读", "其他实用主题", "风格画像"]
        for text in samples:
            with self.subTest(text=text):
                self.assertTrue(any(any(word.lower() in text.lower() for word in rule["keywords"]) for rule in knowledge.load_routes()["keywordRules"]) or any(w in text for w in knowledge.load_routes()["safetyKeywords"]))


if __name__ == "__main__":
    unittest.main()
