"""Shared strict validation of the expected on-device instrumentation checks."""
import json
import re
from pathlib import Path


def expected_checks(root: Path) -> list[str]:
    checks = json.loads((root / 'app/src/fytAndroidTest/assets/smoke-checks.json').read_text())
    if not isinstance(checks, list) or not checks or any(not isinstance(c, str) or not re.fullmatch(r'[a-z][a-z0-9_]+', c) for c in checks) or len(set(checks)) != len(checks):
        raise ValueError('Invalid or duplicate expected smoke check names')
    return checks


def parse_results(output: str, expected: list[str]) -> dict:
    def one(pattern: str) -> str:
        matches = re.findall(pattern, output, re.MULTILINE)
        if len(matches) != 1:
            raise ValueError('Missing or repeated instrumentation result')
        return matches[0]
    passed = int(one(r'^INSTRUMENTATION_RESULT: passed=(\d+)\s*$'))
    failures = int(one(r'^INSTRUMENTATION_RESULT: failures=(\d+)\s*$'))
    checks = json.loads(one(r'^INSTRUMENTATION_RESULT: checks=(\[[^\n]*\])\s*$'))
    code = int(one(r'^INSTRUMENTATION_CODE: (-?\d+)\s*$'))
    if passed != len(expected) or failures != 0 or code != -1 or checks != expected:
        raise ValueError('Instrumentation did not complete the exact expected checks successfully')
    return {'passed': passed, 'failures': failures, 'checks': checks}
