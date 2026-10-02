import unittest
from startup_benchmark import parse_launch


class LaunchParserTest(unittest.TestCase):
    def test_cold_launch_uses_android_total(self):
        self.assertEqual(420, parse_launch('Status: ok\nTotalTime: 420\nWaitTime: 431\n')[0])

    def test_resumed_activity_uses_completed_wait(self):
        self.assertEqual(12, parse_launch('Status: ok\nWaitTime: 12\n')[0])

    def test_missing_or_failed_timings_never_pass_as_zero(self):
        for output in ('Status: timeout\nWaitTime: 1\n', 'Status: ok\n'):
            with self.assertRaises(ValueError):
                parse_launch(output)
