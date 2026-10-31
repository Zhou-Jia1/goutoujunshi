"""Regression for native clicks clipped by scrolling and Android system bars."""
import sys
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from smoke_android_m1 import APP, Device
from smoke_android_import import click_ready


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


class NativeClickTests(unittest.TestCase):
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
