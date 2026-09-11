import unittest
from ci_version_code import MAX_VERSION_CODE, BASE, version_code


class CiVersionCodeTest(unittest.TestCase):
    def test_shared_sequence_exceeds_legacy_codes(self):
        self.assertGreater(version_code(1), 1_000_000 + 1000)
        self.assertEqual(version_code(301), version_code(300) + 1)

    def test_invalid_counts_fail_instead_of_emitting_invalid_apks(self):
        for count in (0, -1, MAX_VERSION_CODE - BASE + 1):
            with self.assertRaises(ValueError):
                version_code(count)


if __name__ == "__main__":
    unittest.main()
