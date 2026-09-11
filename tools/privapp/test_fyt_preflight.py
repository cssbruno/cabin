import unittest
from fyt_preflight import analyze


class FytPreflightTest(unittest.TestCase):
    def test_known_chip_is_not_assumed_flash_compatible(self):
        result = analyze({'ro.board.platform': 'ums512', 'ro.build.version.sdk': '29'})
        self.assertEqual(result['chip_family_hint'], 'UIS7862 / UMS512')
        self.assertTrue(result['android_meets_cabin_minimum'])
        self.assertEqual(result['firmware_installation_compatibility'], 'unverified')
        self.assertEqual(result['privileged_permission_support'], 'unverified')

    def test_conflicting_chips_are_unknown(self):
        self.assertEqual(analyze({'ro.board.platform': 'ums512', 'ro.hardware': 'sc9853i'})['chip_family_hint'], 'unknown or conflicting')

    def test_missing_sdk_is_not_compatible(self):
        self.assertIsNone(analyze({})['android_meets_cabin_minimum'])
        self.assertFalse(analyze({'ro.build.version.sdk': '26'})['android_meets_cabin_minimum'])

    def test_only_allowlisted_properties_are_reported(self):
        self.assertNotIn('ro.serialno', analyze({'ro.serialno': 'private'})['properties'])


if __name__ == '__main__':
    unittest.main()
