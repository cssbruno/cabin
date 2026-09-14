"""Package a verified release APK and the source inputs used to review it.

Run release unit tests, lint, assembleRelease and assembleReleaseAndroidTest with
--rerun-tasks, then tools/run_emulator_smoke.py, before this script. Verification is
mandatory: this script never builds, runs tests, or substitutes debug results.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from smoke_contract import expected_checks, parse_results

ROOT = Path(__file__).resolve().parents[1]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def required_file(path: Path) -> Path:
    require(path.is_file() and path.stat().st_size > 0, f'Missing or empty verification input: {path}')
    return path


def tree_files(folder: Path) -> list[Path]:
    return sorted(p for p in folder.rglob('*') if p.is_file() and '__pycache__' not in p.parts
                  and p.suffix != '.pyc')


def fresh(evidence: Path, inputs: list[Path], purpose: str) -> None:
    required_file(evidence)
    newest = max(inputs, key=lambda p: p.stat().st_mtime_ns)
    require(evidence.stat().st_mtime_ns >= newest.stat().st_mtime_ns,
            f'Stale {purpose}: {evidence} predates {newest}. '
            'Rerun release unit tests, lint, assembleRelease and assembleReleaseAndroidTest '
            'with --rerun-tasks, then rerun emulator smoke.')


def build_tools(sdk: Path | None) -> tuple[Path, Path]:
    if sdk is None:
        configured = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
        if configured:
            sdk = Path(configured)
        else:
            properties = ROOT / 'local.properties'
            if properties.exists():
                match = re.search(r'^sdk\.dir=(.+)$', properties.read_text(), re.MULTILINE)
                if match:
                    sdk = Path(match.group(1).strip().replace('\\:', ':').replace('\\\\', '\\'))
    require(sdk is not None, 'Set ANDROID_HOME/ANDROID_SDK_ROOT or pass --sdk-root to verify the APK.')
    candidates = sorted((sdk / 'build-tools').glob('*'),
                        key=lambda p: tuple(int(n) for n in re.findall(r'\d+', p.name)), reverse=True)
    for directory in candidates:
        aapt, apksigner = directory / 'aapt', directory / 'apksigner'
        if aapt.is_file() and apksigner.is_file():
            return aapt, apksigner
    raise ValueError(f'Android aapt and apksigner are required under {sdk / "build-tools"}')


def command(args: list[str]) -> str:
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
    require(result.returncode == 0, f'APK verification command failed: {args[0]}\n{result.stdout}')
    return result.stdout


def verify_apk(apk: Path, version: str, version_code: int, application_id: str,
               sdk: Path | None) -> dict:
    with zipfile.ZipFile(apk) as archive:
        dex = b''.join(archive.read(name) for name in archive.namelist() if name.endswith('.dex'))
        require(b'com.syu.ipc.IRemoteToolkit' in dex, 'FYT toolkit missing from APK')
        require(b'com.mitsubishielectric' not in dex and b'Lcom/cabin/hondacustom/HondaClient;' not in dex,
                'Original Honda service code must not be packaged in the FYT app')
    aapt, apksigner = build_tools(sdk)
    badging = command([str(aapt), 'dump', 'badging', str(apk)])
    package_line = next((line for line in badging.splitlines() if line.startswith('package: ')), '')
    attributes = dict(re.findall(r"(\w+)='([^']*)'", package_line))
    require(attributes.get('name') == application_id, 'APK application ID does not match release metadata.')
    require(attributes.get('versionName') == version and attributes.get('versionCode') == str(version_code),
            'APK version does not match release output metadata.')
    require(not any(line.startswith('application-debuggable') for line in badging.splitlines()),
            'Refusing to package a debuggable APK as the release variant.')
    signing = command([str(apksigner), 'verify', '--verbose', '--print-certs', str(apk)])
    dn = re.search(r'^Signer #1 certificate DN: (.+)$', signing, re.MULTILINE)
    fingerprint = re.search(r'^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F:]+)$', signing, re.MULTILINE)
    require(dn is not None and fingerprint is not None, 'APK signing verification did not report a signer certificate.')
    require('CN=Android Debug' in dn.group(1),
            'Unexpected signing certificate: this prerelease is configured to retain its development certificate.')
    return {'type': 'development certificate', 'certificate_subject': dn.group(1),
            'certificate_sha256': fingerprint.group(1).replace(':', '').lower(),
            'verified': True, 'debuggable': False}


def source_inputs() -> tuple[list[Path], list[Path]]:
    names = ('README.md', 'README-OEM-reference.md', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
             'gradlew', 'app/build.gradle.kts')
    files = [required_file(ROOT / name) for name in names]
    for optional in ('gradlew.bat', 'LICENSE', 'LICENSE.md', 'NOTICE', 'app/proguard-rules.pro'):
        if (ROOT / optional).is_file():
            files.append(ROOT / optional)
    for folder in ('app/src', 'gradle', 'tools', 'documents'):
        files.extend(tree_files(ROOT / folder))
    files = sorted(set(files))
    for path in files:
        require(path.resolve().is_relative_to(ROOT), f'Source archive input escapes the project: {path}')
    config_names = {'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat'}
    configs = [p for p in files if (p.parent == ROOT and p.name in config_names) or
               p == ROOT / 'app/build.gradle.kts' or p == ROOT / 'app/proguard-rules.pro' or
               p.is_relative_to(ROOT / 'gradle')]
    return files, configs


def unit_results(inputs: list[Path]) -> tuple[ET.Element, int, list[Path]]:
    paths = sorted((ROOT / 'app/build/test-results/testReleaseUnitTest').glob('TEST-*.xml'))
    require(bool(paths), 'Run testReleaseUnitTest before packaging; debug test results are not accepted.')
    merged = ET.Element('testsuites')
    total = 0
    for path in paths:
        fresh(path, inputs, 'release unit-test results')
        suite = ET.parse(path).getroot()
        require(suite.tag == 'testsuite', f'Expected a JUnit testsuite in {path}')
        count = int(suite.get('tests', '0'))
        require(count > 0 and len(suite.findall('testcase')) == count, f'Empty or inconsistent test suite: {path}')
        require(all(int(suite.get(field, '0')) == 0 for field in ('failures', 'errors', 'skipped')),
                f'Unit-test failures, errors, or skips prevent release: {path}')
        require(not any(suite.findall(f'.//{tag}') for tag in ('failure', 'error', 'skipped')),
                f'JUnit contains a failed or skipped case: {path}')
        # A removed test class must not survive as an old XML file and inflate the count.
        class_name = suite.get('name', '').split('.')[-1].split('$')[0]
        require(any(p.stem == class_name for p in inputs if p.suffix in ('.java', '.kt')
                    and p.is_relative_to(ROOT / 'app/src/fytTest')),
                f'JUnit suite has no matching current test source: {suite.get("name")}')
        merged.append(suite)
        total += count
    merged.attrib.update(tests=str(total), failures='0', errors='0', skipped='0')
    return merged, total, paths


def package(sdk: Path | None) -> dict:
    files, configs = source_inputs()
    production = configs + tree_files(ROOT / 'app/src/fyt') + tree_files(ROOT / 'app/src/main/res')
    unit_inputs = production + tree_files(ROOT / 'app/src/fytTest')
    lint_inputs = production + tree_files(ROOT / 'app/src/fytTest') + tree_files(ROOT / 'app/src/fytAndroidTest')
    metadata_path = ROOT / 'app/build/outputs/apk/release/output-metadata.json'
    fresh(metadata_path, production, 'release output metadata')
    metadata = json.loads(metadata_path.read_text())
    require(isinstance(metadata, dict), 'Release metadata must be a JSON object.')
    require(metadata.get('variantName') == 'release', 'APK output metadata must identify the release variant.')
    require(metadata.get('applicationId') == 'com.cabin.hondacustom', 'Unexpected release application ID.')
    elements = metadata.get('elements', [])
    require(isinstance(elements, list) and len(elements) == 1 and isinstance(elements[0], dict) and elements[0].get('outputFile') == 'app-release.apk',
            'Expected one app-release.apk output; split or debug artifacts are not supported.')
    element = elements[0]
    version = element.get('versionName')
    require(isinstance(version, str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', version) is not None,
            'Missing or unsafe versionName in output metadata.')
    version_code = element.get('versionCode')
    require(type(version_code) is int and version_code > 0, 'Missing versionCode in release metadata.')
    apk_input = metadata_path.parent / 'app-release.apk'
    fresh(apk_input, production, 'release APK')
    signing = verify_apk(apk_input, version, version_code, metadata['applicationId'], sdk)
    apk_digest = sha256(apk_input)

    junit, count, junit_inputs = unit_results(unit_inputs)
    lint_xml = ROOT / 'app/build/reports/lint-results-release.xml'
    lint_html = ROOT / 'app/build/reports/lint-results-release.html'
    fresh(lint_xml, lint_inputs, 'release lint XML')
    fresh(lint_html, lint_inputs, 'release lint HTML')
    lint = ET.parse(lint_xml).getroot()
    require(lint.tag == 'issues', 'Unexpected lint XML document.')
    issues = lint.findall('issue')
    require(not any(i.get('severity', '').lower() in ('error', 'fatal') for i in issues),
            'Release lint errors/fatal findings prevent packaging.')

    smoke_json = ROOT / 'app/build/reports/emulator-smoke.json'
    smoke_log = ROOT / 'app/build/reports/emulator-smoke.log'
    instrumentation_inputs = production + tree_files(ROOT / 'app/src/fytAndroidTest')
    test_apk = ROOT / 'app/build/outputs/apk/androidTest/release/app-release-androidTest.apk'
    fresh(test_apk, instrumentation_inputs, 'release instrumentation APK')
    smoke_inputs = instrumentation_inputs + [apk_input, test_apk]
    for runner in ('smoke_contract.py', 'run_emulator_smoke.py'):
        if (ROOT / 'tools' / runner).exists():
            smoke_inputs.append(ROOT / 'tools' / runner)
    fresh(smoke_json, smoke_inputs, 'emulator smoke report')
    fresh(smoke_log, smoke_inputs, 'emulator smoke log')
    smoke = json.loads(smoke_json.read_text())
    require(isinstance(smoke, dict), 'Emulator smoke report must be a JSON object.')
    expected = expected_checks(ROOT)
    require(smoke.get('errors', 0) == 0 and smoke.get('skipped', 0) == 0,
            'Emulator smoke errors or skips prevent packaging.')
    require(smoke.get('apk_sha256') == apk_digest, 'Emulator smoke verified a different APK; rerun smoke for this release APK.')
    require(smoke.get('test_apk_sha256') == sha256(test_apk),
            'Emulator smoke verified a different instrumentation APK; rebuild and rerun smoke.')
    verified = parse_results(smoke_log.read_text(), expected)
    require(all(type(smoke.get(key)) is type(value) and smoke[key] == value for key, value in verified.items()),
            'Emulator smoke log and report disagree.')

    matrix, matrix_inputs = [], []
    for path in sorted((ROOT / 'app/build/reports').glob('emulator-smoke-api*.json')):
        match = re.fullmatch(r'emulator-smoke-api(\d+)\.json', path.name)
        require(match is not None, 'Malformed emulator matrix report filename')
        api = int(match.group(1)); log_path = path.with_suffix('.log')
        fresh(path, smoke_inputs, 'emulator matrix report'); fresh(log_path, smoke_inputs, 'emulator matrix log')
        row = json.loads(path.read_text()); result = parse_results(log_path.read_text(), expected)
        require(all(type(row.get(k)) is type(v) and row[k] == v for k,v in result.items()), 'Emulator matrix log and report disagree')
        require(row.get('apk_sha256') == apk_digest and row.get('test_apk_sha256') == sha256(test_apk), 'Emulator matrix used different APKs')
        require(str(row.get('emulator_api')) == str(api) and row.get('hardware_tested') is False, 'Invalid emulator matrix provenance')
        matrix.append(row); matrix_inputs.extend([path, log_path])
    require(any(str(row['emulator_api']) == '17' for row in matrix), 'Android API17 smoke coverage is required for the declared minimum version')
    require(any(int(row['emulator_api']) >= 35 for row in matrix), 'A modern Android API35+ smoke run is required')

    source_hashes = {str(p.relative_to(ROOT)): sha256(p) for p in files}
    summary = {
        'version': version, 'version_code': version_code, 'variant': 'release',
        'application_id': metadata['applicationId'], 'apk_sha256': apk_digest,
        'debuggable': False, 'signing': signing,
        'tests': count, 'failures': 0, 'errors': 0, 'skipped': 0,
        'unit_test_task': 'testReleaseUnitTest', 'lint_task': 'lintRelease',
        'lint_errors': 0, 'lint_warnings': sum(i.get('severity') == 'Warning' for i in issues),
        'emulator_smoke': smoke, 'emulator_smoke_runs': matrix, 'hardware_tested': False,
        'target': 'FYT/SYU CANBUS module 7',
        'source_sha256': source_hashes,
        'verification_inputs_sha256': {str(p.relative_to(ROOT)): sha256(p)
                                     for p in [metadata_path, *junit_inputs, lint_xml, lint_html, test_apk, smoke_json, smoke_log, *matrix_inputs]},
        'freshness': 'Production/build inputs predate APK, unit XML and lint; test inputs predate their reports; emulator evidence matches APK SHA-256.',
    }
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    # Validate everything before publishing files, then stage a complete artifact set.
    with tempfile.TemporaryDirectory(prefix='.release-', dir=dist) as temp:
        stage = Path(temp)
        apk = stage / f'Honda-Customizer-{version}-FYT.apk'
        shutil.copy2(apk_input, apk)
        source = stage / f'Honda-Customizer-{version}-source.zip'
        with zipfile.ZipFile(source, 'w', zipfile.ZIP_DEFLATED) as archive:
            for path in files:
                archive.write(path, Path('honda-customizer') / path.relative_to(ROOT))
        ET.ElementTree(junit).write(stage / f'test-results-{version}.xml', encoding='utf-8', xml_declaration=True)
        for path, name in ((lint_html, f'lint-results-{version}.html'), (lint_xml, f'lint-results-{version}.xml'),
                           (smoke_json, f'emulator-smoke-{version}.json'), (smoke_log, f'emulator-smoke-{version}.log')):
            shutil.copy2(path, stage / name)
        for path in matrix_inputs:
            shutil.copy2(path, stage / f'{path.stem}-{version}{path.suffix}')
        (stage / f'verification-{version}.json').write_text(json.dumps(summary, indent=2) + '\n')
        artifacts = sorted(stage.iterdir())
        (stage / f'SHA256SUMS-{version}').write_text(''.join(f'{sha256(p)}  {p.name}\n' for p in artifacts))
        require(sha256(apk) == apk_digest and sha256(apk_input) == apk_digest,
                'Release APK changed while packaging; rerun verification.')
        require(source_inputs()[0] == files, 'Source file inventory changed while packaging; rerun verification.')
        require(all(sha256(path) == source_hashes[str(path.relative_to(ROOT))] for path in files),
                'Source inputs changed while packaging; rerun verification.')
        require(all(sha256(ROOT / name) == digest for name, digest in summary['verification_inputs_sha256'].items()),
                'Verification evidence changed while packaging; retry after verification completes.')
        for path in sorted(stage.iterdir()):
            path.replace(dist / path.name)
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk-root', type=Path, help='Android SDK containing aapt and apksigner')
    args = parser.parse_args()
    try:
        summary = package(args.sdk_root)
    except (ValueError, OSError, ET.ParseError, subprocess.SubprocessError) as error:
        print(f'Release packaging refused: {error}', file=sys.stderr)
        return 1
    print(json.dumps({k: summary[k] for k in ('version', 'tests', 'failures', 'hardware_tested', 'apk_sha256')}))
    print(f'Artifacts: {ROOT / "dist"}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
