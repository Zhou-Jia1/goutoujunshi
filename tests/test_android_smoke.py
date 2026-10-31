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


if __name__ == "__main__":
    unittest.main()
