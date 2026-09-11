"""Regression checks for the allowlisted offline firmware-property evaluator."""
import unittest
import javalang
from extract_syu_air import Facts, Unknown


class FirmwareFactsTest(unittest.TestCase):
    def evaluate(self, expression, variant=None):
        return Facts(1, {}, {}, reverse_temperature=variant).expr(
            javalang.parse.parse_expression(expression))

    def test_both_temperature_variants_are_explicit(self):
        expression = 'SystemProperties.getBoolean("persist.fyt.reversetemp", false)'
        self.assertIs(False, self.evaluate(expression, False))
        self.assertIs(True, self.evaluate(expression, True))
        with self.assertRaises(Unknown):
            self.evaluate(expression)

    def test_other_properties_and_defaults_fail_closed(self):
        for expression in (
            'SystemProperties.getBoolean("other.property", false)',
            'SystemProperties.getBoolean("persist.fyt.reversetemp", true)',
        ):
            with self.assertRaises(Unknown):
                self.evaluate(expression, False)


if __name__ == '__main__':
    unittest.main()
