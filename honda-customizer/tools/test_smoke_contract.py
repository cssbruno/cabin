import json
import unittest
from smoke_contract import parse_results

class SmokeContractTest(unittest.TestCase):
    names = ['install', 'native_settings', 'validation_report']
    def log(self, names=None, passed=3, failures=0, code=-1):
        return (f'INSTRUMENTATION_RESULT: passed={passed}\nINSTRUMENTATION_RESULT: failures={failures}\n'
                f'INSTRUMENTATION_RESULT: checks={json.dumps(self.names if names is None else names)}\n'
                f'INSTRUMENTATION_CODE: {code}\n')
    def test_complete_expected_checks_pass(self):
        self.assertEqual(self.names, parse_results(self.log(), self.names)['checks'])
    def test_same_count_different_checks_rejected(self):
        with self.assertRaises(ValueError):parse_results(self.log(['install','install','install']),self.names)
    def test_incomplete_suite_rejected(self):
        with self.assertRaises(ValueError):parse_results(self.log(self.names[:2],2),self.names)
    def test_failure_rejected_even_with_full_check_list(self):
        with self.assertRaises(ValueError):parse_results(self.log(failures=1),self.names)
    def test_process_failure_code_rejected(self):
        with self.assertRaises(ValueError):parse_results(self.log(code=0),self.names)
    def test_duplicate_result_rejected(self):
        with self.assertRaises(ValueError):parse_results(self.log()+'INSTRUMENTATION_RESULT: passed=3\n',self.names)
    def test_missing_raw_results_rejected(self):
        with self.assertRaises(ValueError):parse_results('PASS 3 emulator smoke checks',self.names)

if __name__=='__main__':unittest.main()
