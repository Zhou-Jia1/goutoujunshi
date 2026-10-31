import importlib.util
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("android_boundaries", Path(__file__).resolve().parents[1] / "scripts/check_android_boundaries.py")
boundary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(boundary)


class BoundaryTest(unittest.TestCase):
    def test_domain_cannot_gain_dependencies_through_formatting(self):
        for call in ['project(":app")', 'project( ":app" )', 'project(path = ":app")', 'project(\n path\n=\n":app"\n)']:
            with self.subTest(call=call):
                self.assertTrue(boundary.dependency_errors("core/domain", f"implementation({call})"))

    def test_named_or_multiline_allowed_dependencies_remain_valid(self):
        self.assertEqual([], boundary.dependency_errors("app", 'api(project(\npath = ":core:data"\n))'))
        self.assertEqual([], boundary.dependency_errors("core/data", 'api(project( ":core:domain" ))'))

    def test_designsystem_cannot_import_a_business_module(self):
        self.assertTrue(boundary.dependency_errors("core/designsystem", 'api(project(path = ":core:domain"))'))


if __name__ == "__main__":
    unittest.main()
