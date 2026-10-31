"""Check synthetic chat sharing on an explicitly selected, empty test emulator.

Uses Junshi's debug package and a single synthetic contact, which it deletes.
No files are shared. Temporary imports are discarded by stopping the debug process.
"""
import argparse
import re
from pathlib import Path

from smoke_android_m1 import APP, Device, require, validate_apk

MESSAGE = "synthetic_t21_message"
SHARED = "synthetic_t21_speaker:" + MESSAGE
CONTACT = "synthetic_t21_contact"
CONFIRM = "确认说话人并导入"


def labels(device):
    return [node.get("text") for node in device.tree().iter("node")
            if node.get("package") == APP and node.get("text")]


def share(device, cold=False):
    if cold:
        device.call("shell", "am", "force-stop", APP)
    result = device.call("shell", "am", "start", "-W", "-n",
        f"{APP}/com.shengjidaguai.goutoujunshi.MainActivity",
        "-a", "android.intent.action.SEND", "-t", "text/plain",
        "--es", "android.intent.extra.TEXT", SHARED)
    require("Status: ok" in result, "Debug sharing activity did not start")
    current = labels(device)
    require("确认聊天记录" in current and MESSAGE in current,
        "Explicit share did not reach the unconfirmed preview")
    require(device.action(device.tree(), CONFIRM) is None,
        "Unconfirmed speakers must not be importable")


def confirm_import(device, formal=False):
    if "我了解并有权使用这些记录" in labels(device):
        device.click("我了解并有权使用这些记录")
    if formal:
        node = device.editor()
        require(not node.get("text"), "Refusing to overwrite a nonempty alias")
        left, top, right, bottom = device.bounds(node)
        device.call("shell", "input", "tap", (left + right) // 2, (top + bottom) // 2)
        device.call("shell", "input", "text", CONTACT)
        device.call("shell", "input", "keyevent", "KEYCODE_BACK")
        require(device.editor().get("text") == CONTACT, "Synthetic alias was changed")
        device.click("使用此昵称建立新联系人")
    else:
        device.click("导入到：请选择临时问问或联系人")
        device.click("临时问问")
    device.click("这条消息是谁说的：尚未确认", scroll=True)
    device.click("我")
    device.click(CONFIRM, scroll=True)
    require("确认聊天记录" not in labels(device), "Confirmed import did not finish")


def remove_contact(device):
    device.start(restart=True)
    device.click("联系人")
    current = labels(device)
    if CONTACT in current:
        require(current.count("删除联系人") == 1, "Refusing ambiguous fixture deletion")
        device.click("删除联系人")
        device.click("删除这些记录")
        require(CONTACT not in labels(device), "Synthetic contact was not removed")
    device.click("军师")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", required=True, type=Path)
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
    device.click("联系人")
    require("还没有联系人" in labels(device), "Use an empty dedicated test emulator")
    device.click("军师")
    try:
        share(device, cold=True)
        confirm_import(device)
        print("PASS: cold sharing requires target and speaker confirmation", flush=True)
        # Identical explicit hot deliveries are new imports, then deduplicated on confirmation.
        share(device)
        confirm_import(device)
        require("这段之前导入过，已保留原有记录。" in labels(device),
            "Repeated content did not report deduplication")
        print("PASS: identical hot sharing is accepted and deduplicated", flush=True)
        device.click("发送", scroll=True)
        device.click("全部复制", scroll=True)
        require("已复制。发送前再看一眼，确认像你想说的话。" in labels(device),
            "Confirmed evidence did not reach bundled knowledge and demo generation")
        print("PASS: confirmed evidence alone supports demo generation and copy", flush=True)
        device.start(restart=True)
        # Opening a request preview with no draft/evidence must produce the empty-input error.
        device.click("查看实际准备的内容", scroll=True)
        require("先说说你想问什么。" in labels(device),
            "Temporary import unexpectedly survived process death")
        print("PASS: temporary evidence disappears after process death", flush=True)
        device.click("关闭")
        share(device)
        confirm_import(device, formal=True)
        device.start(restart=True)
        require(CONTACT in labels(device), "Formal import target did not survive process death")
        device.click("发送", scroll=True)
        device.click("全部复制", scroll=True)
        require("已复制。发送前再看一眼，确认像你想说的话。" in labels(device),
            "Formal evidence was not restored for analysis")
        print("PASS: formal encrypted evidence supports analysis after process death", flush=True)
    finally:
        remove_contact(device)


if __name__ == "__main__":
    main()
