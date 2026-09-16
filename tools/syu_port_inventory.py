#!/usr/bin/env python3
"""Offline reference inventory. This is a work list, never a semantic coverage claim."""
import argparse
import hashlib
import json
import re
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--sources', type=Path, required=True, help='JADX source directory, development only')
parser.add_argument('--registry', type=Path, default=Path('app/src/main/assets/syu/protocols-2023.json'))
parser.add_argument('--output', type=Path, default=Path('documents/research/SYU-PORT-INVENTORY.json'))
args = parser.parse_args()
registry = json.loads(args.registry.read_text())
root = args.sources / 'com/syu/carinfo'
if not root.is_dir():
    parser.error('sources must contain com/syu/carinfo')
# Literal references locate work. Computed IDs, helper calls, superclass behavior,
# native code and view resource labels still need individual review.
screens = {}
for path in sorted(root.rglob('*.java')):
    text = path.read_text()
    if 'DataCanbus' not in text:
        continue
    name = str(path.relative_to(args.sources)).removesuffix('.java').replace('/', '.')
    screens[name] = {
        'sourceSha256': hashlib.sha256(path.read_bytes()).hexdigest(),
        'literalFields': sorted(set(map(int, re.findall(r'DataCanbus\.DATA\[(\d+)\]', text))) - {1000}),
        'literalCommands': sorted(set(map(int, re.findall(r'\.cmd\(\s*(\d+)\s*[,)]', text)))),
        'requiresIndividualReview': True,
        'decompilerWarnings': 'JADX ERROR' in text or 'Method not decompiled' in text,
    }
families = {}
for profile, slot in sorted(registry['profiles'].items(), key=lambda item: int(item[0])):
    template = registry['templates'][slot]
    callback = template['callback'] or 'UNRESOLVED'
    entry = families.setdefault(callback, {'profiles': [], 'templateSlots': [], 'fullParityVerified': False})
    entry['profiles'].append(int(profile))
    if slot not in entry['templateSlots']:
        entry['templateSlots'].append(slot)
result = {
    'schema': 1,
    'purpose': 'Development work inventory. Literal references are not complete behavior or implemented coverage.',
    'registrySha256': hashlib.sha256(args.registry.read_bytes()).hexdigest(),
    'profileCount': len(registry['profiles']),
    'callbackFamilies': families,
    'referenceScreens': screens,
    'remainingAudit': ['Computed fields and commands', 'Profile-dependent branches', 'Units and unavailable sentinels',
                       'Commands and feedback differences', 'Views, labels and option availability',
                       'Inheritance and helper methods', 'Per-profile local regression evidence'],
}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
print(f'{len(registry["profiles"])} profiles, {len(families)} callback families, {len(screens)} source screens/helpers')
