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


def find_text(device, text, scroll=False):
    """Wait for real UI output and reach it on compact emulator screens."""
    for upward in ([True, False] if scroll else [True]):
        for _ in range(8):
            root = device.tree()
            if any(node.get("package") == APP and node.get("text") == text
                   for node in root.iter("node")):
                return root
            if scroll:
                device.swipe(root, upward)
    raise AssertionError(f"Expected debug text was not reachable: {text}")


def click_ready(device, text):
    # Popup composition can lag behind its anchor click. Never repeat the anchor action.
    for _ in range(8):
        root = device.tree()
        node = device.action(root, text)
        if node is not None:
            parents = {child: parent for parent in root.iter() for child in parent}
            left, top, right, bottom = device.bounds(node)
            parent = parents.get(node)
            while parent is not None:
                if parent.get("scrollable") == "true":
                    a, b, c, d = device.bounds(parent)
                    left, top, right, bottom = max(left, a), max(top, b), min(right, c), min(bottom, d)
                parent = parents.get(parent)
            # Edge-to-edge Compose nodes can include pixels covered by system bars.
            # Never tap Android's Home/Back area instead of the visible app control.
            for bar in root.iter("node"):
                if bar.get("package") != APP:
                    continue
                if bar.get("resource-id") == "android:id/navigationBarBackground":
                    a, b, c, d = device.bounds(bar)
                    if a <= left and c >= right and b > 0:
                        bottom = min(bottom, b)
                elif bar.get("resource-id") == "android:id/statusBarBackground":
                    a, b, c, d = device.bounds(bar)
                    if a <= left and c >= right:
                        top = max(top, d)
            if right > left and bottom > top:
                device.call("shell", "input", "tap", (left + right) // 2, (top + bottom) // 2)
                return
    public_controls = {"我", "对方", "其他人（保留昵称）", "关闭", CONFIRM,
        "这条消息是谁说的：尚未确认", "这条消息是谁说的：我"}
    print("Visible import controls:", [(n.get("text"), n.get("bounds"), n.get("enabled"))
        for n in device.tree().iter("node") if n.get("package") == APP and n.get("text") in public_controls], flush=True)
    raise AssertionError(f"Expected debug action was not ready: {text}")


def share(device, cold=False):
    if cold:
        device.call("shell", "am", "force-stop", APP)
    result = device.call("shell", "am", "start", "-W", "-n",
        f"{APP}/com.shengjidaguai.goutoujunshi.MainActivity",
        "-a", "android.intent.action.SEND", "-t", "text/plain",
        "--es", "android.intent.extra.TEXT", SHARED)
    require("Status: ok" in result, "Debug sharing activity did not start")
    find_text(device, "确认聊天记录")
    current = labels(device)
    rights_pending = any(text.startswith("请只导入") for text in current)
    find_text(device, MESSAGE, scroll=True)
    root = find_text(device, CONFIRM, scroll=True)
    require(device.action(root, CONFIRM) is None,
        "Unconfirmed speakers must not be importable")
    return rights_pending


def confirm_import(device, formal=False, rights_pending=False):
    if rights_pending:
        device.click("我了解并有权使用这些记录", scroll=True)
    if formal:
        find_text(device, "给对方起个代号，尽量不用真实姓名", scroll=True)
        node = device.editor()
        require(not node.get("text"), "Refusing to overwrite a nonempty alias")
        left, top, right, bottom = device.bounds(node)
        device.call("shell", "input", "tap", (left + right) // 2, (top + bottom) // 2)
        device.call("shell", "input", "text", CONTACT)
        device.call("shell", "input", "keyevent", "KEYCODE_BACK")
        require(device.editor().get("text") == CONTACT, "Synthetic alias was changed")
        device.click("使用此昵称建立新联系人", scroll=True)
    else:
        device.click("导入到：请选择临时问问或联系人", scroll=True)
        device.click("临时问问")
    speaker_label = "这条消息是谁说的：尚未确认"
    find_text(device, speaker_label, scroll=True)
    click_ready(device, speaker_label)
    click_ready(device, "我")
    find_text(device, "这条消息是谁说的：我", scroll=True)
    device.click(CONFIRM, scroll=True)
    require("确认聊天记录" not in labels(device), "Confirmed import did not finish")


def remove_contact(device):
    device.start(restart=True)
    click_ready(device, "联系人")
    current = labels(device)
    if CONTACT in current:
        require(current.count("删除联系人") == 1, "Refusing ambiguous fixture deletion")
        click_ready(device, "删除联系人")
        click_ready(device, "删除这些记录")
        require(CONTACT not in labels(device), "Synthetic contact was not removed")
    click_ready(device, "军师")


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
    click_ready(device, "联系人")
    require("还没有联系人" in labels(device), "Use an empty dedicated test emulator")
    click_ready(device, "军师")
    try:
        rights = share(device, cold=True)
        confirm_import(device, rights_pending=rights)
        print("PASS: cold sharing requires target and speaker confirmation", flush=True)
        # Identical explicit hot deliveries are new imports, then deduplicated on confirmation.
        rights = share(device)
        confirm_import(device, rights_pending=rights)
        find_text(device, "这段之前导入过，已保留原有记录。", scroll=True)
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
        rights = share(device)
        confirm_import(device, formal=True, rights_pending=rights)
        device.start(restart=True)
        require(CONTACT in labels(device), "Formal import target did not survive process death")
        device.click("发送", scroll=True)
        device.click("全部复制", scroll=True)
        require("已复制。发送前再看一眼，确认像你想说的话。" in labels(device),
            "Formal evidence was not restored for analysis")
        print("PASS: formal encrypted evidence supports analysis after process death", flush=True)
    finally:
        try:
            remove_contact(device)
        except Exception:
            # Only process/window metadata is emitted; no UI text or conversation dump.
            try:
                print("Debug process:", device.call("shell", "pidof", APP).strip(), flush=True)
            except Exception:
                print("Debug process is not running", flush=True)
            try:
                focus = device.call("shell", "dumpsys", "window", "windows")
                print("Focused windows:", [line.strip() for line in focus.splitlines()
                    if "mCurrentFocus=" in line or "mFocusedApp=" in line], flush=True)
            except Exception:
                print("Focused window metadata unavailable", flush=True)
            raise


if __name__ == "__main__":
    main()
