"""Regression for native clicks clipped by scrolling and Android system bars."""
import sys
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from smoke_android_m1 import (APP, ALIAS, Device, CONTACTS_ACTIVE, CONTACT_OVERVIEW,
                              DELETE_CONTACT, CONFIRM_DELETE, EMPTY_CONTACTS)
from smoke_android_import import CONTACT, click_ready, remove_contact, wait_contacts_view


class FakeDevice(Device):
    bounds = staticmethod(Device.bounds)
    action = Device.action

    def __init__(self, body):
        self.root = ET.fromstring(f'<hierarchy>{body}</hierarchy>')
        self.calls = []

    def tree(self):
        return self.root

    def call(self, *args):
        self.calls.append(args)


def button(bounds, text="联系人"):
    return f'<node package="{APP}" bounds="{bounds}" clickable="true" enabled="true"><node package="{APP}" text="{text}"/></node>'


class ScrollingContactDevice(Device):
    """Native XML changes only after real driver swipes/taps; click is never stubbed."""
    def __init__(self, alias, duplicate_delete=False):
        self.alias, self.duplicate_delete = alias, duplicate_delete
        self.stage, self.swipes = "list", 0
        self.calls, self.actions = [], []
    def start(self, restart=False):
        self.stage = "list"
    def tree(self):
        def text(label): return f'<node package="{APP}" text="{label}"/>'
        nav = button("[112,576][208,640]", "联系人") + button("[0,576][112,640]", "军师")
        if self.stage == "list":
            body = text("联系人") + text(CONTACTS_ACTIVE) + button("[16,180][280,244]", self.alias) + nav
        elif self.stage == "detail":
            body = text(self.alias) + text(CONTACT_OVERVIEW)
            visible = text("虚构长详情内容")
            if self.swipes >= 3:
                visible += button("[40,400][280,464]", DELETE_CONTACT)
                if self.duplicate_delete: visible += button("[40,480][280,544]", DELETE_CONTACT)
            body += f'<node package="{APP}" scrollable="true" bounds="[0,80][320,560]">{visible}</node>' + nav
        elif self.stage == "confirm":
            body = text(DELETE_CONTACT) + button("[40,300][280,364]", CONFIRM_DELETE)
        elif self.stage == "empty":
            body = text("联系人") + text(EMPTY_CONTACTS) + nav
        else:
            raise AssertionError("Unexpected synthetic navigation state")
        return ET.fromstring(f'<hierarchy>{body}</hierarchy>')
    def call(self, *args):
        self.calls.append(args)
        if args[:3] == ("shell", "input", "swipe"):
            if self.stage != "detail": raise AssertionError("Unexpected cleanup scroll")
            self.swipes += 1
        elif args[:3] == ("shell", "input", "tap"):
            x, y = args[3:5]
            hits = []
            for node in self.tree().iter("node"):
                if node.get("clickable") == "true":
                    left, top, right, bottom = self.bounds(node)
                    if left <= x < right and top <= y < bottom:
                        hits.append(next(child.get("text") for child in node.iter("node") if child.get("text")))
            if len(hits) != 1: raise AssertionError("Tap must hit one actual visible native control")
            label = hits[0]; self.actions.append(label)
            if label == self.alias: self.stage = "detail"
            elif label == DELETE_CONTACT:
                if self.swipes < 3: raise AssertionError("Deletion was unreachable before scrolling")
                self.stage = "confirm"
            elif label == CONFIRM_DELETE: self.stage = "empty"
            elif label == "军师": self.stage = "counsel"


class NativeClickTests(unittest.TestCase):
    def test_resource_labels_match_real_choicefield_text(self):
        self.assertEqual("联系人：正在联系", CONTACTS_ACTIVE)
        self.assertEqual("档案：概览", CONTACT_OVERVIEW)

    def test_both_cleanup_paths_scroll_real_native_controls_before_one_delete(self):
        for alias, cleanup in [(ALIAS, Device.remove_fixture), (CONTACT, remove_contact)]:
            with self.subTest(alias=alias):
                device = ScrollingContactDevice(alias)
                cleanup(device)
                self.assertEqual(["联系人", alias, DELETE_CONTACT, CONFIRM_DELETE, "军师"], device.actions)
                self.assertEqual(3, device.swipes)
                self.assertEqual(1, device.actions.count(DELETE_CONTACT))
                self.assertEqual(1, device.actions.count(CONFIRM_DELETE))
                self.assertEqual(5, sum(call[:3] == ("shell", "input", "tap") for call in device.calls))

    def test_scrolled_duplicate_delete_actions_are_rejected_without_dangerous_tap(self):
        device = ScrollingContactDevice(CONTACT, duplicate_delete=True)
        with self.assertRaisesRegex(AssertionError, "ambiguous debug action"):
            remove_contact(device)
        self.assertEqual(["联系人", CONTACT], device.actions)
        self.assertEqual(3, device.swipes)

    def test_click_waits_for_stable_position_and_never_taps_old_frame(self):
        class MovingDevice(FakeDevice):
            def __init__(self):
                super().__init__(button("[40,100][280,164]", "发送"))
                self.reads = 0
            def tree(self):
                self.reads += 1
                if self.reads >= 2:
                    return ET.fromstring('<hierarchy>' + button("[40,200][280,264]", "发送") + '</hierarchy>')
                return self.root
        device = MovingDevice()
        device.click("发送")
        self.assertEqual([("shell", "input", "tap", 160, 232)], device.calls)
        self.assertGreaterEqual(device.reads, 3)

    def test_scroll_scan_uses_overlapping_slow_gestures(self):
        device = FakeDevice(f'<node package="{APP}" scrollable="true" bounds="[0,80][320,560]"/>')
        device.swipe(device.tree(), True)
        call = device.calls[0]
        self.assertLess(abs(call[4] - call[6]), (560 - 80) / 2)
        self.assertGreaterEqual(call[7], 600)

    def test_editor_reaches_composer_below_a_full_answer_card(self):
        class LongAnswerDevice(FakeDevice):
            def __init__(self):
                super().__init__(f'<node package="{APP}" scrollable="true" bounds="[0,80][320,560]"/>')
                self.swipes = 0
            def tree(self):
                if self.swipes >= 13:
                    return ET.fromstring(f'<hierarchy><node package="{APP}" scrollable="true" bounds="[0,80][320,560]">'
                        f'<node package="{APP}" class="android.widget.EditText" bounds="[16,400][304,480]" text="synthetic_saved_draft"/></node></hierarchy>')
                return self.root
            def swipe(self, root, upward):
                self.assert_upward = upward
                self.swipes += 1
        device = LongAnswerDevice()
        self.assertEqual("synthetic_saved_draft", device.editor().get("text"))
        self.assertEqual(13, device.swipes)
        self.assertTrue(device.assert_upward)
        self.assertEqual([], device.calls)

    def test_missing_editor_search_stays_bounded_and_redacts_private_content(self):
        device = FakeDevice(f'<node package="{APP}" scrollable="true" bounds="[0,80][320,560]">'
            f'<node package="{APP}" text="synthetic_private_body"/></node>')
        with patch("builtins.print") as output:
            with self.assertRaisesRegex(AssertionError, "not reachable"):
                device.editor()
        self.assertLessEqual(len(device.calls), 40)
        self.assertNotIn("synthetic_private_body", repr(output.call_args_list))

    def test_cleanup_window_gap_only_waits_while_owned_activity_is_resumed(self):
        class GapDevice:
            def __init__(self, resumed): self.resumed, self.reads = resumed, 0
            def tree(self):
                self.reads += 1
                if self.reads == 1: raise AssertionError("Debug app must remain in foreground")
                return ET.fromstring(f'<hierarchy><node package="{APP}" text="联系人"/>'
                    f'<node package="{APP}" text="联系人"/><node package="{APP}" text="还没有联系人"/></hierarchy>')
            def call(self, *args): return "topResumedActivity=" + self.resumed
        owned = GapDevice(APP + "/com.shengjidaguai.goutoujunshi.MainActivity")
        self.assertIn("还没有联系人", wait_contacts_view(owned))
        foreign = GapDevice("com.android.launcher3/.Launcher")
        with self.assertRaisesRegex(AssertionError, "remain in foreground"):
            wait_contacts_view(foreign)
        self.assertEqual(1, foreign.reads)

    def test_cleanup_never_deletes_when_contact_actions_are_ambiguous(self):
        class AmbiguousDevice:
            def start(self, restart=False): pass
            def tree(self):
                return ET.fromstring('<hierarchy>' + ''.join(f'<node package="{APP}" text="{label}"/>'
                    for label in ("联系人", "联系人", CONTACTS_ACTIVE, CONTACT, CONTACT)) + '</hierarchy>')
        clicks = []
        with patch("smoke_android_import.click_ready", side_effect=lambda _, text, **kwargs: clicks.append(text)):
            with self.assertRaisesRegex(AssertionError, "safe cleanup"):
                remove_contact(AmbiguousDevice())
        self.assertEqual(["联系人"], clicks)

    def test_cleanup_waits_for_contact_screen_before_unique_deletion(self):
        def frame(*labels):
            return ET.fromstring('<hierarchy>' + ''.join(
                f'<node package="{APP}" text="{label}"/>' for label in labels) + '</hierarchy>')
        class TransitionDevice:
            def __init__(self):
                self.frames = [frame("军师", "联系人", CONTACT),
                    frame("联系人", "联系人", CONTACTS_ACTIVE, CONTACT)]
                self.starts = 0
                self.reads = 0
            def start(self, restart=False): self.starts += 1
            def tree(self):
                self.reads += 1
                return self.frames.pop(0) if len(self.frames) > 1 else self.frames[0]
        device = TransitionDevice()
        clicks = []
        def click(_, text, **kwargs):
            clicks.append(text)
            if text == CONTACT:
                self.assertGreaterEqual(device.reads, 2, "Must wait for the active contact list before opening its owner")
                device.frames = [frame(CONTACT, CONTACT_OVERVIEW, "删除联系人")]
            elif text == "删除联系人":
                self.assertEqual(CONTACT, device.frames[0].find("node").get("text"))
                device.frames = [frame("删除联系人", "删除这些记录")]
            elif text == "删除这些记录":
                device.frames = [frame("联系人", "联系人", "还没有联系人")]
        with patch("smoke_android_import.click_ready", side_effect=click):
            remove_contact(device)
        self.assertEqual(1, device.starts)
        self.assertEqual(["联系人", CONTACT, "删除联系人", "删除这些记录", "军师"], clicks)
        self.assertEqual(1, clicks.count("删除联系人"))
        self.assertEqual(1, clicks.count("删除这些记录"))
        self.assertIn("还没有联系人", [node.get("text") for node in device.tree().iter("node")])

    def test_cleanup_rejects_wrong_owner_detail_before_deleting(self):
        class WrongOwnerDevice:
            def __init__(self): self.opened = False
            def start(self, restart=False): pass
            def tree(self):
                labels = ("其他合成联系人", CONTACT_OVERVIEW, "删除联系人") if self.opened else ("联系人", "联系人", CONTACTS_ACTIVE, CONTACT)
                return ET.fromstring('<hierarchy>' + ''.join(f'<node package="{APP}" text="{label}"/>' for label in labels) + '</hierarchy>')
        device = WrongOwnerDevice()
        clicks = []
        def click(_, text, **kwargs):
            clicks.append(text)
            if text == CONTACT: device.opened = True
        with patch("smoke_android_import.click_ready", side_effect=click):
            with self.assertRaisesRegex(AssertionError, "detail owner"):
                remove_contact(device)
        self.assertEqual(["联系人", CONTACT], clicks)

    def test_diagnostic_never_emits_unknown_user_text_or_description(self):
        root = ET.fromstring(f'<hierarchy><node package="{APP}" text="synthetic_private_body" '
            'content-desc="synthetic_private_alias" bounds="[0,0][10,10]"/>'
            f'<node package="{APP}" text="发送" bounds="[0,10][10,20]"/></hierarchy>')
        output = repr(Device.redacted_controls(root))
        self.assertNotIn("synthetic_private", output)
        self.assertIn("发送", output)
        self.assertIn("[0,10][10,20]", output)

    def test_shared_click_clips_send_control_to_scroll_viewport(self):
        device = FakeDevice(f'<node scrollable="true" bounds="[0,80][320,560]">{button("[40,548][280,612]", "发送")}</node>')
        device.click("发送")
        self.assertEqual([("shell", "input", "tap", 160, 554)], device.calls)

    def test_navigation_click_stays_above_android_home_bar(self):
        device = FakeDevice(button("[112,576][208,640]") +
            f'<node package="{APP}" resource-id="android:id/navigationBarBackground" bounds="[0,592][320,640]"/>')
        click_ready(device, "联系人")
        self.assertEqual([("shell", "input", "tap", 160, 584)], device.calls)

    def test_partly_visible_control_uses_scroll_intersection(self):
        device = FakeDevice(f'<node scrollable="true" bounds="[0,80][320,560]">{button("[40,548][280,612]")}</node>')
        click_ready(device, "联系人")
        self.assertEqual([("shell", "input", "tap", 160, 554)], device.calls)


if __name__ == "__main__":
    unittest.main()
