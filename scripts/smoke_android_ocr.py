"""Check the real Photo Picker and OCR read-error recovery on an empty test emulator.

Requires API 33+ with a system Photo Picker. Uses only the debug package's fixed
synthetic provider and an intentionally nonexistent URI. No image is selected,
saved, uploaded, or deleted; application data is never cleared.
"""
import argparse
import re
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from smoke_android_m1 import APP, Device, require, resource_text, validate_apk
from smoke_android_import import find_text, labels


def design_text(name):
    resources = Path(__file__).resolve().parents[1] / 'android/core/designsystem/src/main/res/values/strings.xml'
    node = ET.parse(resources).getroot().find(f"string[@name='{name}']")
    require(node is not None, 'Required design-system resource is missing')
    return ''.join(node.itertext())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--aapt2', type=Path)
    args = parser.parse_args()
    require(re.fullmatch(r'emulator-\d+', args.serial), 'Select a dedicated test emulator')
    require(args.apk.is_file(), 'Debug APK does not exist')
    validate_apk(args.apk, args.aapt2)
    device = Device(args.adb, args.serial)
    require(int(device.call('shell', 'getprop', 'ro.build.version.sdk').strip()) >= 33,
            'This system Photo Picker check requires API 33 or higher')
    resolved = device.call('shell', 'cmd', 'package', 'resolve-activity', '--brief',
                          '-a', 'android.provider.action.PICK_IMAGES', '-t', 'image/*')
    component = resolved.strip().splitlines()[-1]
    require(re.fullmatch(r'[\w.]+/[\w.$]+', component), 'No system Photo Picker resolves')
    device.call('install', '-r', args.apk.resolve())
    device.start(restart=True)
    device.click(resource_text('nav_contacts'))
    require(resource_text('no_contacts') in labels(device), 'Use an empty dedicated test app')
    device.click(resource_text('nav_counsel'))
    device.click(design_text('ds_screenshots'), unique=True)
    for _ in range(20):
        activities = device.call('shell', 'dumpsys', 'activity', 'activities')
        if any('topResumedActivity=' in line and component in line for line in activities.splitlines()):
            break
        time.sleep(.2)
    else:
        raise AssertionError('Screenshot entry did not open the resolved system Photo Picker')
    print('PASS: screenshot entry opens the real system Photo Picker', flush=True)
    device.call('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    find_text(device, resource_text('nav_counsel'))
    require(resource_text('import_title') not in labels(device), 'Picker cancellation created an import')
    print('PASS: real picker cancellation creates no import preview', flush=True)
    device.call('shell', 'am', 'start', '-W', '-n',
                f'{APP}/com.shengjidaguai.goutoujunshi.MainActivity',
                '-a', 'android.intent.action.SEND', '-t', 'image/png',
                '--eu', 'android.intent.extra.STREAM', f'content://{APP}.synthetic-ocr/missing')
    find_text(device, resource_text('import_title'))
    device.click(resource_text('ocr_start'), scroll=True)
    find_text(device, resource_text('ocr_read'), scroll=True)
    root = find_text(device, resource_text('import_save'), scroll=True)
    require(device.action(root, resource_text('import_save')) is None,
            'Unreadable image unexpectedly became importable')
    print('PASS: unreadable content URI shows the read error and cannot be imported', flush=True)
    device.click(resource_text('close'), unique=True)
    find_text(device, design_text('ds_paste'))
    print('PASS: closing the OCR error restores the paste entry', flush=True)


if __name__ == '__main__':
    main()
