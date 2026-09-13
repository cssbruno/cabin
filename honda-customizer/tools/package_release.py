"""Package the already-built development APK and its reproducible source inputs."""
from pathlib import Path
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
dist = root / 'dist'
dist.mkdir(exist_ok=True)
tests = list((root / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
assert tests, 'Run the tests before packaging'
test_root = ET.Element('testsuites')
count = 0
for path in sorted(tests):
    suite = ET.parse(path).getroot()
    assert int(suite.get('failures', 0)) == int(suite.get('errors', 0)) == 0, path
    count += int(suite.get('tests', 0))
    test_root.append(suite)
lint = ET.parse(root / 'app/build/reports/lint-results-debug.xml').getroot()
assert not any(i.get('severity') in ('Error', 'Fatal') for i in lint.findall('issue'))
apk = dist / 'Honda-Customizer-2.0-original-HU.apk'
shutil.copy2(root / 'app/build/outputs/apk/debug/app-debug.apk', apk)
ET.ElementTree(test_root).write(dist / 'test-results-2.0.xml', encoding='utf-8', xml_declaration=True)
shutil.copy2(root / 'app/build/reports/lint-results-debug.html', dist / 'lint-results-2.0.html')
shutil.copy2(root / 'app/build/reports/lint-results-debug.xml', dist / 'lint-results-2.0.xml')
summary = {'version': '2.0', 'tests': count, 'failures': 0,
           'lint_errors': 0, 'lint_warnings': sum(i.get('severity') == 'Warning' for i in lint.findall('issue')),
           'hardware_tested': False, 'target': 'Original Mitsubishi Electric Honda head unit'}
(dist / 'verification-2.0.json').write_text(json.dumps(summary, indent=2) + '\n')
files = [root / name for name in ('README.md', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'app/build.gradle.kts')]
for folder in ('app/src', 'gradle/wrapper', 'tools'):
    files += [p for p in (root / folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts]
source = dist / 'Honda-Customizer-2.0-source.zip'
with zipfile.ZipFile(source, 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(set(files)):
        archive.write(path, Path('honda-customizer') / path.relative_to(root))
artifacts = [apk, source, dist/'test-results-2.0.xml', dist/'lint-results-2.0.html', dist/'lint-results-2.0.xml', dist/'verification-2.0.json']
(dist / 'SHA256SUMS-2.0').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in artifacts))
print(json.dumps(summary))
print(f'APK: {apk} ({apk.stat().st_size:,} bytes)')
