"""Check floating-counsel lifecycle on an empty, unlocked API35+ test emulator.

Installs only the validated debug APK, grants its two test permissions and uses
its SystemUI tile. Abrupt death signals only the exact same-UID debug process.
No application storage is cleared, no credentials are entered and no chat is
captured. The test stops its service and removes only a tile it added.
"""
import argparse
import re
import subprocess
import time
from pathlib import Path

from smoke_android_m1 import APP, Device, require, resource_text, validate_apk
from smoke_android_import import find_text, labels
from smoke_android_model_settings import MISSING

TILE = f'{APP}/com.shengjidaguai.goutoujunshi.floating.FloatingCounselTile'


def wait_for(check, message, timeout=12):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(.2)
    raise AssertionError(message)


class FloatingDevice:
    def __init__(self, device):
        self.device = device
        self.uid = device.call('shell', 'run-as', APP, 'id', '-u').strip()
        require(self.uid.isdigit(), 'Cannot identify the debug package UID')

    def service(self):
        raw = self.device.call('shell', 'dumpsys', 'activity', 'services', APP)
        return bool(re.search(r'ServiceRecord\{[^\n]*' + re.escape(APP) +
                              r'/[^\n]*FloatingCounselService', raw))

    def notification(self):
        raw = self.device.call('shell', 'cmd', 'notification', 'list')
        return any(APP in line and '|3101|' in line for line in raw.splitlines())

    def window(self):
        raw = self.device.call('shell', 'dumpsys', 'window', '-a')
        for block in re.split(r'(?=  Window #\d+)', raw):
            kind = re.search(r'mAttrs=[\s\S]*?\bty=([A-Z_]+)', block)
            owner = re.search(r'\bmOwnerUid=(\d+)', block)
            if (kind and owner and kind.group(1) == 'APPLICATION_OVERLAY' and
                    owner.group(1) == self.uid and 'mHasSurface=true' in block and
                    'isOnScreen=true' in block):
                return True
        return False

    def running(self):
        return self.service() and self.notification() and self.window()

    def stopped(self):
        return not self.service() and not self.notification() and not self.window()

    def click_tile(self):
        self.device.call('shell', 'cmd', 'statusbar', 'expand-settings')
        self.device.call('shell', 'cmd', 'statusbar', 'click-tile', TILE)
        self.device.call('shell', 'cmd', 'statusbar', 'collapse')


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
    require(int(device.call('shell', 'getprop', 'ro.build.version.sdk').strip()) >= 35,
            'This platform lifecycle check requires API35 or higher')
    device.call('install', '-r', args.apk.resolve())
    device.call('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    device.call('shell', 'wm', 'dismiss-keyguard')
    device.start(restart=True)
    device.click(resource_text('nav_contacts'))
    require(resource_text('no_contacts') in labels(device), 'Use an empty dedicated test app')
    device.click(resource_text('nav_me'))
    find_text(device, MISSING, scroll=True)
    find_text(device, '模式：演示（不联网）', scroll=True)
    device.call('shell', 'appops', 'set', APP, 'SYSTEM_ALERT_WINDOW', 'allow')
    device.call('shell', 'pm', 'grant', APP, 'android.permission.POST_NOTIFICATIONS')
    floating = FloatingDevice(device)
    require(floating.stopped(), 'Opening the app must not automatically start floating counsel')
    tiles = device.call('shell', 'settings', 'get', 'secure', 'sysui_qs_tiles')
    added_tile = APP not in tiles
    primary_error = None
    try:
        if added_tile:
            device.call('shell', 'cmd', 'statusbar', 'add-tile', TILE)
        device.call('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        floating.click_tile()
        wait_for(floating.running, 'One tile click did not create the service, window and notification')
        print('PASS: one SystemUI tile click starts floating counsel from the background', flush=True)
        floating.click_tile()
        wait_for(floating.stopped, 'Tile stop left a service, window or notification')
        print('PASS: one tile click removes the service, window and notification', flush=True)

        floating.click_tile()
        wait_for(floating.running, 'The service did not restart after an explicit tile click')
        pid = device.call('shell', 'pidof', APP).strip()
        require(re.fullmatch(r'\d+', pid), 'Refusing an ambiguous process target')
        name = device.call('shell', 'run-as', APP, 'cat', f'/proc/{pid}/cmdline').strip('\0').strip()
        require(name == APP, 'Refusing to signal a process outside the exact debug package')
        device.call('shell', 'run-as', APP, 'kill', '-9', pid)
        wait_for(floating.stopped, 'Abrupt process death left floating-counsel resources')
        time.sleep(5)
        require(floating.stopped(), 'The killed process automatically restarted floating counsel')
        device.start()
        require(floating.stopped(), 'Reopening the app automatically restarted floating counsel')
        print('PASS: abrupt death of the running debug process and app reopening do not restore the ball', flush=True)

        floating.click_tile()
        wait_for(floating.running, 'Explicit restart after process death failed')
        device.call('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
        wait_for(floating.stopped, 'Screen off left floating-counsel resources')
        device.call('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        device.call('shell', 'wm', 'dismiss-keyguard')
        require(floating.stopped(), 'Screen unlock automatically restarted floating counsel')
        print('PASS: screen off stops floating counsel and unlocking does not reopen it', flush=True)
    except BaseException as error:
        primary_error = error
        raise
    finally:
        cleanup = [('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP'),
                   ('shell', 'wm', 'dismiss-keyguard'),
                   ('shell', 'cmd', 'statusbar', 'collapse'),
                   ('shell', 'am', 'force-stop', APP)]
        if added_tile:
            cleanup.append(('shell', 'cmd', 'statusbar', 'remove-tile', TILE))
        for command in cleanup:
            try:
                device.call(*command)
            except (OSError, subprocess.SubprocessError) as error:
                if primary_error is None:
                    raise
                add_note = getattr(primary_error, 'add_note', None)
                if add_note:
                    add_note('A task-owned emulator cleanup command also failed')


if __name__ == '__main__':
    main()
