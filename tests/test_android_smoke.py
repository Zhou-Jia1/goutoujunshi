import importlib.util
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("android_smoke", Path(__file__).resolve().parents[1] / "scripts/smoke_android_m1.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class ApkIdentityTest(unittest.TestCase):
    def test_only_expected_debuggable_package_is_accepted(self):
        smoke.validate_badging(f"package: name='{smoke.APP}' versionCode='1'\napplication-debuggable\n")

    def test_other_packages_are_rejected_before_install(self):
        for package in ["com.example.other", "com.shengjidaguai.goutoujunshi", ""]:
            with self.subTest(package=package), self.assertRaises(ValueError):
                smoke.validate_badging(f"package: name='{package}'\napplication-debuggable\n")

    def test_debug_namespace_without_debuggable_flag_is_rejected(self):
        with self.assertRaises(ValueError):
            smoke.validate_badging(f"package: name='{smoke.APP}' versionCode='1'\n")


class ContactDetailCleanupTest(unittest.TestCase):
    class FakeDevice:
        def __init__(self, aliases=1):
            self.stage = "list"
            self.aliases = aliases
            self.actions = []
        def start(self, restart=False):
            pass
        def tree(self):
            import xml.etree.ElementTree as ET
            root = ET.Element("hierarchy")
            labels = {"list": ["联系人", smoke.CONTACTS_ACTIVE] + [smoke.ALIAS] * self.aliases,
                      "detail": [smoke.ALIAS, smoke.CONTACT_OVERVIEW, smoke.DELETE_CONTACT],
                      "confirm": ["删除这些记录"], "empty": ["联系人", "还没有联系人"]}[self.stage]
            for label in labels:
                ET.SubElement(root, "node", {"package": smoke.APP, "text": label})
            return root
        def click(self, text, scroll=False, unique=False):
            self.actions.append(text)
            if text == smoke.ALIAS: self.stage = "detail"
            if text == "删除联系人": self.stage = "confirm"
            if text == "删除这些记录": self.stage = "empty"
    def test_cleanup_navigates_unique_fixture_to_real_detail_before_delete(self):
        device = self.FakeDevice()
        smoke.Device.remove_fixture(device)
        self.assertEqual(["联系人", smoke.ALIAS, "删除联系人", "删除这些记录", "军师"], device.actions)
    def test_ambiguous_alias_refuses_before_opening_or_deleting(self):
        device = self.FakeDevice(aliases=2)
        with self.assertRaises(AssertionError): smoke.Device.remove_fixture(device)
        self.assertEqual(["联系人"], device.actions)


if __name__ == "__main__":
    unittest.main()
