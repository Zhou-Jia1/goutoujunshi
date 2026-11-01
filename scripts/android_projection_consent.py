"""Interact with the real AOSP projection dialog on a dedicated test emulator.

Call only after validating the debug APK and an empty NoKey/Demo fixture. This
helper changes no app-op or projection grant: each start uses the system dialog.
No UI dump is printed or saved. The lifecycle smoke never requests a frame.
"""
import re
import time
import xml.etree.ElementTree as ET

from smoke_android_m1 import APP, require

SYSTEM_UI = "com.android.systemui"
PERMISSION_ACTIVITY = "MediaProjectionPermissionActivity"


def own_projection_dialog(activity_dump):
    """Require the resumed SystemUI record to name this exact debug caller."""
    resumed = re.search(r"(?:topResumedActivity=|mResumedActivity:)\s*ActivityRecord\{([^\n]+)", activity_dump)
    if not resumed or SYSTEM_UI not in resumed.group(1) or PERMISSION_ACTIVITY not in resumed.group(1):
        return False
    token = resumed.group(1).split()[0]
    for block in re.split(r"(?=\s*\* Hist\s+#\d+:)", activity_dump):
        first_line = block.lstrip().splitlines()[0] if block.strip() else ""
        if (f'ActivityRecord{{{token} ' in first_line and SYSTEM_UI in first_line
                and PERMISSION_ACTIVITY in first_line):
            caller = re.search(r"\blaunchedFromPackage=([^\s]+)", block)
            if caller and caller.group(1) == APP:
                return True
    return False


def system_tree(device):
    raw = device.call('exec-out', 'uiautomator', 'dump', '/dev/tty')
    start = raw.find('<?xml')
    end = raw.find('</hierarchy>')
    require(start >= 0 and end > start, 'System permission UI did not expose a hierarchy')
    return ET.fromstring(raw[start:end + len('</hierarchy>')])


def system_node(root, *, resource=None, text=None):
    matches = [node for node in root.iter('node')
               if node.get('package') == SYSTEM_UI
               and node.get('enabled') == 'true'
               and (resource is None or node.get('resource-id') == resource)
               and (text is None or node.get('text') == text)]
    require(len(matches) == 1, 'Expected one enabled control in the system projection dialog')
    return matches[0]


def tap(device, node):
    values = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
    require(len(values) == 4, 'System control has no usable bounds')
    left, top, right, bottom = values
    require(right > left and bottom > top, 'System control is not visible')
    device.call('shell', 'input', 'tap', (left + right) // 2, (top + bottom) // 2)


def projection_consent(device, *, allow=True, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if own_projection_dialog(device.call('shell', 'dumpsys', 'activity', 'activities')):
            break
        time.sleep(.2)
    else:
        raise AssertionError('No real projection permission dialog from the validated debug app')
    root = system_tree(device)
    if not allow:
        tap(device, system_node(root, resource='android:id/button2'))
        return
    # The AOSP API35 fixture uses English or Chinese SystemUI. Select the full
    # display only for lifecycle checks, with no capture request made afterwards.
    tap(device, system_node(root, resource=f'{SYSTEM_UI}:id/screen_share_mode_spinner'))
    root = system_tree(device)
    options = [node for node in root.iter('node')
               if node.get('package') == SYSTEM_UI and node.get('enabled') == 'true'
               and node.get('text') in {'Entire screen', '整个屏幕', '整个屏幕内容'}]
    require(len(options) == 1, 'Select a supported AOSP test fixture locale')
    tap(device, options[0])
    require(own_projection_dialog(device.call('shell', 'dumpsys', 'activity', 'activities')),
            'Projection permission owner changed before consent')
    tap(device, system_node(system_tree(device), resource='android:id/button1'))
