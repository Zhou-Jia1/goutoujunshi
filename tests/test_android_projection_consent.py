import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from android_projection_consent import APP, own_projection_dialog
from smoke_android_projection_stop import FRAMEWORK, stop_owned_projection


def record(token, caller):
    return (f'    * Hist  #0: ActivityRecord{{{token} u0 com.android.systemui/'
            '.mediaprojection.permission.MediaProjectionPermissionActivity t1}\n'
            f'      launchedFromUid=10234 launchedFromPackage={caller} launchedFromFeature=null\n')


def resumed(token):
    return (f'    mResumedActivity: ActivityRecord{{{token} u0 com.android.systemui/'
            '.mediaprojection.permission.MediaProjectionPermissionActivity t1}\n')


class ProjectionConsentCallerTest(unittest.TestCase):
    def test_current_debug_caller_is_accepted(self):
        self.assertTrue(own_projection_dialog(record('a123', APP) + resumed('a123')))

    def test_api35_top_resumed_caller_is_accepted(self):
        dump = 'ACTIVITY MANAGER ACTIVITIES\n' + record('a123', APP) + resumed('a123').replace('mResumedActivity: ', 'topResumedActivity=')
        self.assertTrue(own_projection_dialog(dump))

    def test_other_app_dialog_is_rejected(self):
        self.assertFalse(own_projection_dialog(record('b456', 'another.app') + resumed('b456')))

    def test_old_debug_dialog_cannot_authorize_other_current_dialog(self):
        dump = record('a123', APP) + record('b456', 'another.app') + resumed('b456')
        self.assertFalse(own_projection_dialog(dump))

    def test_permission_activity_must_be_resumed(self):
        self.assertFalse(own_projection_dialog(record('a123', APP)))


class ProjectionStopOwnershipTest(unittest.TestCase):
    def assert_rejected_without_system_stop(self, dump, fingerprint=FRAMEWORK):
        class Device:
            def __init__(self):
                self.stops = 0

            def call(self, *args):
                if args == ('shell', 'getprop', 'ro.build.fingerprint'):
                    return fingerprint
                if args == ('shell', 'su', '0', 'id', '-u'):
                    return '0\n'
                if args == ('shell', 'dumpsys', 'media_projection'):
                    return dump
                if args == ('shell', 'su', '0', 'service', 'call', 'media_projection', '7'):
                    self.stops += 1
                    return "Result: Parcel(\t00000000    '....')\n"
                raise RuntimeError('Unexpected fixture command')

        device = Device()
        with self.assertRaises(AssertionError):
            stop_owned_projection(device, '10153')
        self.assertEqual(0, device.stops)

    def test_other_package_projection_is_never_stopped(self):
        self.assert_rejected_without_system_stop('(another.app, uid=10153): TYPE_SCREEN_CAPTURE')

    def test_other_uid_of_debug_package_is_never_stopped(self):
        self.assert_rejected_without_system_stop(f'({APP}, uid=10154): TYPE_SCREEN_CAPTURE')

    def test_ambiguous_projection_history_is_never_stopped(self):
        self.assert_rejected_without_system_stop(
            f'({APP}, uid=10153): TYPE_SCREEN_CAPTURE\n(another.app, uid=10154): TYPE_SCREEN_CAPTURE')

    def test_unknown_framework_cannot_use_fixed_binder_transaction(self):
        self.assert_rejected_without_system_stop(f'({APP}, uid=10153): TYPE_SCREEN_CAPTURE', 'other-build')
