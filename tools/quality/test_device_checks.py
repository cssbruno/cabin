import unittest

from device_checks import instrumentation_passed


class InstrumentationOutcomeTest(unittest.TestCase):
    def test_complete_success(self):
        self.assertTrue(instrumentation_passed('Time: 71\n\nOK (2 tests)\n\nINSTRUMENTATION_CODE: -1\n'))

    def test_shell_zero_is_not_test_success(self):
        self.assertFalse(instrumentation_passed('FAILURES!!!\nTests run: 2, Failures: 1\nINSTRUMENTATION_CODE: -1\n'))
        self.assertFalse(instrumentation_passed('INSTRUMENTATION_ABORTED: Process crashed\n'))

    def test_partial_or_wrong_suite_is_not_success(self):
        self.assertFalse(instrumentation_passed('OK (1 test)\nINSTRUMENTATION_CODE: -1\n'))
        self.assertFalse(instrumentation_passed('OK (2 tests)\n'))
        self.assertFalse(instrumentation_passed('OK (2 tests)\nINSTRUMENTATION_FAILED: runner crashed\nINSTRUMENTATION_CODE: -1\n'))


if __name__ == '__main__':
    unittest.main()
