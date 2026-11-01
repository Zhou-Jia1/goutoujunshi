"""Exercise only Junshi's debug APK on a dedicated, empty test emulator.

Never clears application data, reads an API key, or uses real conversations. UI trees
are streamed into this process instead of being written into shared device storage.
"""
import argparse
import os
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

APP = "com.shengjidaguai.goutoujunshi.debug"
ALIAS = "synthetic_m1_contact"
DRAFT = "synthetic_m1_saved_draft"
TEMPORARY = "synthetic_m1_ephemeral_only"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def validate_badging(output):
    package = re.search(r"(?m)^package:\s+name='([^']+)'", output)
    if package is None or package.group(1) != APP:
        raise ValueError("Refusing to install an APK outside Junshi's debug package")
    if not re.search(r"(?m)^application-debuggable\s*$", output):
        raise ValueError("Refusing to install a non-debuggable APK")


def validate_apk(apk, aapt2):
    if aapt2 is None:
        aapt2 = shutil.which("aapt2")
        if aapt2 is None:
            sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
            if sdk:
                aapt2 = Path(sdk) / "build-tools/37.0.0" / ("aapt2.exe" if os.name == "nt" else "aapt2")
    if aapt2 is None or not Path(aapt2).is_file():
        raise ValueError("Provide --aapt2 or configure the project's Android SDK build tools")
    output = subprocess.check_output([str(aapt2), "dump", "badging", str(apk.resolve())],
        text=True, encoding="utf-8", errors="replace", timeout=30)
    validate_badging(output)


class Device:
    def __init__(self, adb, serial):
        self.command = [adb, "-s", serial]

    def call(self, *arguments):
        return subprocess.check_output(self.command + list(map(str, arguments)),
            text=True, encoding="utf-8", errors="replace", timeout=30)

    def start(self, restart=False):
        if restart:
            self.call("shell", "am", "force-stop", APP)
        result = self.call("shell", "am", "start", "-W", "-n", f"{APP}/com.shengjidaguai.goutoujunshi.MainActivity")
        require("Status: ok" in result, "Debug activity did not start")
        require(self.call("shell", "pidof", APP).strip(), "Debug process is not running")

    def tree(self):
        raw = self.call("exec-out", "uiautomator", "dump", "/dev/tty")
        begin = raw.find("<?xml")
        end = raw.find("</hierarchy>") + len("</hierarchy>")
        require(begin >= 0 and end > begin, "No UI tree returned")
        root = ET.fromstring(raw[begin:end])
        require(any(node.get("package") == APP for node in root.iter("node")), "Debug app must remain in foreground")
        return root

    @staticmethod
    def bounds(node):
        values = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
        require(len(values) == 4, "UI node has no usable bounds")
        return values

    def action(self, root, text):
        parents = {child: parent for parent in root.iter() for child in parent}
        for node in root.iter("node"):
            if node.get("package") != APP or node.get("text") != text:
                continue
            while node is not None and node.get("clickable") != "true":
                node = parents.get(node)
            if node is not None and node.get("package") == APP and node.get("enabled") == "true":
                return node
        return None

    def swipe(self, root, upward):
        area = next((node for node in root.iter("node")
            if node.get("package") == APP and node.get("scrollable") == "true"), None)
        require(area is not None, "No scroll area in debug app")
        left, top, right, bottom = self.bounds(area)
        x = (left + right) // 2
        high, low = int(top + (bottom - top) * .3), int(top + (bottom - top) * .7)
        self.call("shell", "input", "swipe", x, low if upward else high, x, high if upward else low, 800)

    def visible_bounds(self, root, node):
        parents = {child: parent for parent in root.iter() for child in parent}
        left, top, right, bottom = self.bounds(node)
        parent = parents.get(node)
        while parent is not None:
            if parent.get("scrollable") == "true":
                a, b, c, d = self.bounds(parent)
                left, top, right, bottom = max(left, a), max(top, b), min(right, c), min(bottom, d)
            parent = parents.get(parent)
        for bar in root.iter("node"):
            if bar.get("package") != APP:
                continue
            if bar.get("resource-id") == "android:id/navigationBarBackground":
                a, b, c, d = self.bounds(bar)
                if a <= left and c >= right and b > 0:
                    bottom = min(bottom, b)
            elif bar.get("resource-id") == "android:id/statusBarBackground":
                a, b, c, d = self.bounds(bar)
                if a <= left and c >= right:
                    top = max(top, d)
        return left, top, right, bottom

    @staticmethod
    def redacted_controls(root):
        public = {"军师", "联系人", "我的", "发送", "停止", "重试", "全部复制"}
        resources = Path(__file__).resolve().parents[1] / "android/app/src/main/res/values/strings.xml"
        if resources.is_file():
            public.update("".join(node.itertext()) for node in ET.parse(resources).getroot()
                          if node.tag == "string")
        # Structural information and exact checked-in UI strings only. Arbitrary user
        # text, aliases, output and field descriptions are never returned.
        return [(node.get("text") if node.get("text") in public else "<redacted>",
                 node.get("content-desc") if node.get("content-desc") in public else "<redacted>",
                 node.get("class"), node.get("bounds"), node.get("enabled"),
                 node.get("clickable"), node.get("scrollable"), node.get("selected"))
                for node in root.iter("node") if node.get("package") == APP][:200]

    def click(self, text, scroll=False):
        observed_public = set()
        previous = None
        for upward in ([True, False] if scroll else [True]):
            for _ in range(10 if scroll else 8):
                root = self.tree()
                for row in self.redacted_controls(root):
                    observed_public.update(value for value in row[:2] if value != "<redacted>")
                node = self.action(root, text)
                if node is not None:
                    left, top, right, bottom = self.visible_bounds(root, node)
                    if right > left and bottom > top:
                        position = (left, top, right, bottom)
                        if position == previous:
                            self.call("shell", "input", "tap", (left + right) // 2, (top + bottom) // 2)
                            return
                        previous = position
                        continue
                previous = None
                if scroll:
                    self.swipe(root, upward)
        print("Redacted action state:", self.redacted_controls(root), flush=True)
        print("Observed checked-in public labels:", sorted(observed_public), flush=True)
        raise AssertionError(f"Debug action not available: {text}")

    def editor(self):
        # Full answers place the composer below a taller card. Small overlapping
        # gestures need a separate bounded scan budget from short action lookups.
        for _ in range(40):
            root = self.tree()
            for node in root.iter("node"):
                if node.get("package") == APP and node.get("class") == "android.widget.EditText":
                    return node
            self.swipe(root, True)
        print("Redacted editor state:", self.redacted_controls(root), flush=True)
        raise AssertionError("Debug text field is not reachable")

    def type_exact(self, text):
        require(text in (ALIAS, DRAFT, TEMPORARY), "Only synthetic fixtures may be entered")
        node = self.editor()
        require(not node.get("text"), "Refusing to overwrite an existing draft")
        left, top, right, bottom = self.bounds(node)
        self.call("shell", "input", "tap", (left + right) // 2, (top + bottom) // 2)
        self.call("shell", "input", "text", text)
        # This smoke suite explicitly enables the soft IME on its dedicated emulator.
        self.call("shell", "input", "keyevent", "KEYCODE_BACK")
        require(self.editor().get("text") == text, "Fast input was changed or reordered")

    def remove_fixture(self):
        self.start(restart=True)
        self.click("联系人")
        root = self.tree()
        labels = [node.get("text") for node in root.iter("node") if node.get("package") == APP]
        require(ALIAS in labels and labels.count("删除联系人") == 1, "Refusing ambiguous deletion")
        self.click("删除联系人")
        self.click("删除这些记录")
        require(all(node.get("text") != ALIAS for node in self.tree().iter("node")), "Synthetic contact was not removed")
        self.click("军师")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True, help="A dedicated test emulator, never a personal device")
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--aapt2", type=Path, help="SDK aapt2 used to verify identity before installation")
    parser.add_argument("--check-apk-only", action="store_true")
    args = parser.parse_args()
    require(re.fullmatch(r"emulator-\d+", args.serial), "Only an explicitly selected test emulator is supported")
    require(args.apk.is_file(), "Debug APK does not exist")
    validate_apk(args.apk, args.aapt2)
    print("PASS: debug APK identity checked before any device mutation", flush=True)
    if args.check_apk_only:
        return
    device = Device(args.adb, args.serial)
    device.call("install", "-r", args.apk.resolve())
    device.call("shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", 1)
    device.start(restart=True)
    device.click("联系人")
    require(any(node.get("text") == "还没有联系人" for node in device.tree().iter("node")), "Use an empty dedicated test emulator")
    device.click("军师")
    device.click("临时问问")
    device.click("新建联系人")
    device.type_exact(ALIAS)
    device.click("新建联系人")
    device.type_exact(DRAFT)
    print("PASS: native fast input is exact", flush=True)
    device.click("发送", scroll=True)
    device.click("全部复制", scroll=True)
    require(any(node.get("text") == "已复制。发送前再看一眼，确认像你想说的话。" for node in device.tree().iter("node")), "Demo reply copy did not complete")
    print("PASS: bundled knowledge, demo generation and native copy", flush=True)
    device.start(restart=True)
    require(device.editor().get("text") == DRAFT, "Formal encrypted draft was not restored")
    print("PASS: formal draft survives process death", flush=True)
    device.start(restart=True)
    device.click(ALIAS)
    device.click("临时问问")
    device.type_exact(TEMPORARY)
    device.start(restart=True)
    require(device.editor().get("text") == DRAFT, "Temporary content persisted or formal draft was lost")
    print("PASS: temporary content disappears after process death", flush=True)
    device.remove_fixture()
    print("PASS: only the synthetic contact was removed", flush=True)


if __name__ == "__main__":
    main()
