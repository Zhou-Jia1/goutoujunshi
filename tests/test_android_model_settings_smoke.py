"""The destructive smoke cleanup must match the displayed empty-data scope."""
import sys
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from smoke_android_m1 import APP, Device
from smoke_android_model_settings import clear_fixtures


class ClearDevice(Device):
    def __init__(self, counts=(0, 0, 0)):
        self.stage = 'settings'
        self.counts = counts
        self.clears = 0
        self.starts = 0

    def start(self, restart=False):
        self.starts += 1
        self.stage = 'settings'

    def tree(self):
        def button(label, top):
            return (f'<node package="{APP}" bounds="[0,{top}][300,{top+60}]" clickable="true" enabled="true">'
                    f'<node package="{APP}" text="{label}"/></node>')
        if self.stage == 'settings':
            body = button('我的', 0) + button('清除全部本机资料', 100)
        elif self.stage == 'confirm':
            a, b, c = self.counts
            text = f'删除 {a} 个联系人、{b} 条记忆、{c} 份聊天记录，无法恢复'
            body = f'<node package="{APP}" text="{text}"/>' + button(text, 120)
        else:
            body = button('清除后重新开始', 100)
        return ET.fromstring(f'<hierarchy>{body}</hierarchy>')

    def call(self, *args):
        if args[:3] == ('shell', 'input', 'tap'):
            y = int(args[4])
            if self.stage == 'settings' and y > 100:
                self.stage = 'confirm'
            elif self.stage == 'confirm':
                self.clears += 1
                self.stage = 'cleared'

    def swipe(self, root, upward):
        pass


class ModelSettingsClearScopeTest(unittest.TestCase):
    def test_empty_counted_confirmation_uses_real_native_button_lookup(self):
        device = ClearDevice()
        clear_fixtures(device)
        self.assertEqual(1, device.clears)
        self.assertEqual('cleared', device.stage)

    def test_any_nonempty_count_refuses_destructive_confirmation(self):
        for counts in ((1, 0, 0), (0, 1, 0), (0, 0, 1)):
            with self.subTest(counts=counts):
                device = ClearDevice(counts)
                with self.assertRaises(AssertionError):
                    clear_fixtures(device)
                self.assertEqual(0, device.clears)
                self.assertEqual('confirm', device.stage)


if __name__ == '__main__':
    unittest.main()
