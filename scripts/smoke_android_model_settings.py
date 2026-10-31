"""Exercise fake-key storage on an empty dedicated emulator, without network calls.

This suite intentionally uses the app's full-clear UI to remove its own fixtures.
Never run it on a personal device or an app containing data you want to keep.
"""
import argparse
import re
from pathlib import Path

from smoke_android_m1 import APP, Device, require, validate_apk
from smoke_android_import import click_ready, find_text, labels

FAKE_KEY = "sk-synthetic-t24-1234"
MASK = "已保存：••••1234"
MISSING = "尚未保存 API Key"


def open_settings(device, restart=False):
    device.start(restart=restart)
    click_ready(device, "我的")


def clear_fixtures(device):
    open_settings(device, restart=True)
    find_text(device, "清除全部本机资料", scroll=True)
    click_ready(device, "清除全部本机资料")
    device.click("清除全部资料")
    find_text(device, "清除后重新开始", scroll=True)
    click_ready(device, "清除后重新开始")


def key_editor(device):
    for upward in (True, False):
        for _ in range(8):
            root = device.tree()
            node = next((n for n in root.iter("node") if n.get("package") == APP and
                n.get("class") == "android.widget.EditText" and n.get("content-desc", "").startswith("API Key")), None)
            if node is not None:
                left, top, right, bottom = device.bounds(node)
                parents = {child: parent for parent in root.iter() for child in parent}
                parent = parents.get(node)
                while parent is not None:
                    if parent.get("scrollable") == "true":
                        a, b, c, d = device.bounds(parent)
                        left, top, right, bottom = max(left, a), max(top, b), min(right, c), min(bottom, d)
                    parent = parents.get(parent)
                if right > left and bottom > top:
                    return node, (left, top, right, bottom)
            device.swipe(root, upward)
    raise AssertionError("Secure key field was not reachable")


def type_fake_key(device):
    node, (left, top, right, bottom) = key_editor(device)
    require(not node.get("text"), "Refusing to overwrite a key editor")
    device.call("shell", "input", "tap", (left + right) // 2, (top + bottom) // 2)
    device.call("shell", "input", "text", FAKE_KEY)
    require(all(FAKE_KEY not in (n.get("text", "") + n.get("content-desc", ""))
        for n in device.tree().iter("node") if n.get("package") == APP), "Raw fixture key was exposed")
    device.call("shell", "input", "keyevent", "KEYCODE_BACK")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--aapt2", type=Path)
    args = parser.parse_args()
    require(re.fullmatch(r"emulator-\d+", args.serial), "Select a dedicated emulator")
    require(args.apk.is_file(), "Debug APK does not exist")
    validate_apk(args.apk, args.aapt2)
    device = Device(args.adb, args.serial)
    device.call("install", "-r", args.apk.resolve())
    device.call("shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", 1)
    device.start(restart=True)
    click_ready(device, "联系人")
    require("还没有联系人" in labels(device), "Refusing to clear a nonempty test app")
    click_ready(device, "我的")
    find_text(device, "模式：演示（不联网）", scroll=True)
    find_text(device, MISSING, scroll=True)
    # These gates precede every fixture mutation and the full-clear cleanup authority.
    cleaned = False
    try:
        type_fake_key(device)
        find_text(device, "保存模型设置", scroll=True)
        click_ready(device, "保存模型设置")
        find_text(device, MASK, scroll=True)
        print("PASS: secure native input and masked encrypted-key storage", flush=True)
        open_settings(device, restart=True)
        find_text(device, MASK, scroll=True)
        print("PASS: stored key survives process restart with masked UI", flush=True)
        find_text(device, "移除已保存密钥", scroll=True)
        click_ready(device, "移除已保存密钥")
        find_text(device, MISSING, scroll=True)
        print("PASS: explicit key removal", flush=True)
        clear_fixtures(device)
        open_settings(device, restart=True)
        find_text(device, MISSING, scroll=True)
        cleaned = True
        print("PASS: full wipe and fresh process have no configured key", flush=True)
    finally:
        if not cleaned:
            clear_fixtures(device)


if __name__ == "__main__":
    main()
