"""Exercise real OS projection-stop callbacks on an empty NoKey/Demo fixture.

Only the explicitly selected AOSP API35 userdebug build is supported. After real
SystemUI consent, its system service stops the exact debug UID's sole projection.
The app process must survive while its projection, window, FGS and notification
disappear. No frame is requested and no application permission is changed.
"""
import argparse
import re
import subprocess
from pathlib import Path

from android_projection_consent import projection_consent
from smoke_android_floating import FloatingDevice, TILE, wait_for
from smoke_android_import import find_text, labels
from smoke_android_m1 import APP, Device, require, resource_text, validate_apk
from smoke_android_model_settings import MISSING

FRAMEWORK = 'Android/sdk_phone64_x86_64/emu64x:15/AE3A.240806.019/12368160:userdebug/test-keys'


def projection_grants(dump):
    return re.findall(r'\(([^,\n]+), uid=(\d+)\): (TYPE_[A-Z_]+)', dump)


def stop_owned_projection(device, uid):
    require(device.call('shell', 'getprop', 'ro.build.fingerprint').strip() == FRAMEWORK,
            'Refusing a Binder transaction on an unverified framework build')
    require(re.fullmatch(r'\d+', uid), 'Expected the exact debug package UID')
    require(device.call('shell', 'su', '0', 'id', '-u').strip() == '0',
            'This owned userdebug fixture requires su for the system-stop test')
    require(projection_grants(device.call('shell', 'dumpsys', 'media_projection')) ==
            [(APP, uid, 'TYPE_SCREEN_CAPTURE')],
            'Refusing to stop a projection outside the exact debug UID or with ambiguous ownership')
    # AOSP android-15.0.0_r1 IMediaProjectionManager.aidl: stopActiveProjection is
    # transaction 7 with no parameters. Never use this build-specific call in App code.
    result = device.call('shell', 'su', '0', 'service', 'call', 'media_projection', '7')
    require(re.fullmatch(r"Result: Parcel\(\s*00000000\s+'\.\.\.\.'\)\s*", result),
            'System stop did not return a successful empty Binder reply')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--aapt2', type=Path)
    args = parser.parse_args()
    require(re.fullmatch(r'emulator-\d+', args.serial), 'Select a dedicated test emulator')
    require(args.apk.is_file(), 'Debug APK does not exist')
    validate_apk(args.apk, args.aapt2)
    device = Device(args.adb, args.serial)
    require(device.call('shell', 'getprop', 'ro.build.fingerprint').strip() == FRAMEWORK,
            'Select the verified AOSP API35 userdebug fixture')
    require(not projection_grants(device.call('shell', 'dumpsys', 'media_projection')),
            'An existing projection must finish before starting this test')
    device.call('install', '-r', args.apk.resolve())
    device.start(restart=True)
    device.click(resource_text('nav_contacts'))
    require(resource_text('no_contacts') in labels(device), 'Use an empty dedicated test app')
    device.click(resource_text('nav_me'))
    find_text(device, MISSING, scroll=True)
    find_text(device, '模式：演示（不联网）', scroll=True)
    floating = FloatingDevice(device)
    require(floating.stopped(), 'The fixture must start with floating counsel off')
    added_tile = APP not in device.call('shell', 'settings', 'get', 'secure', 'sysui_qs_tiles')
    primary_error = None
    try:
        if added_tile:
            device.call('shell', 'cmd', 'statusbar', 'add-tile', TILE)
        device.call('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        floating.click_tile()
        projection_consent(device)
        wait_for(floating.running, 'Projection did not start after fresh system consent')
        pid = device.call('shell', 'pidof', APP).strip()
        require(re.fullmatch(r'\d+', pid), 'Expected one live debug app process')
        stop_owned_projection(device, floating.uid)
        wait_for(floating.stopped, 'OS stop callback left projection, overlay, service or notification')
        require(device.call('shell', 'pidof', APP).strip() == pid,
                'The app process died instead of completing its OS stop callback')
        require('Junshi single frame' not in device.call('shell', 'dumpsys', 'display'),
                'OS stop left the owned VirtualDisplay')
        device.start()
        require(floating.stopped(), 'App reopening restored a system-stopped projection')
        print('PASS: actual OS stop callback releases all floating resources while the app survives', flush=True)
        print('PASS: reopening after OS stop requires a new projection authorization', flush=True)
    except BaseException as error:
        primary_error = error
        raise
    finally:
        cleanup = [('shell', 'cmd', 'statusbar', 'collapse'), ('shell', 'am', 'force-stop', APP)]
        if added_tile:
            cleanup.append(('shell', 'cmd', 'statusbar', 'remove-tile', TILE))
        for command in cleanup:
            try:
                device.call(*command)
            except (OSError, subprocess.SubprocessError):
                if primary_error is None:
                    raise
                primary_error.add_note('An owned-fixture cleanup command also failed')


if __name__ == '__main__':
    main()
