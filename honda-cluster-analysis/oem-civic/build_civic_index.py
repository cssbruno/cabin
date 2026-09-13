"""Build a Civic-only applicability index; candidate IDs are NOT verified bindings."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
catalog = {r['identity']: r for r in json.loads((ROOT.parent / 'complete-settings-catalog.json').read_text())['settings']}
manual_url = 'https://www.honda.com.br/pos-venda/automoveis/sites/customer_service/files/2020-01/Civic%202020%20-%20Manual%20do%20propriet%C3%A1rio_20200128.pdf'
mirror_url = 'https://pdfcoffee.com/civic-2020-manual-do-proprietario-pdf-free.html'

# Feature names are compact paraphrases. OEM IDs/labels/values are from the supplied
# local firmware. A name match is deliberately weaker than a validated binding.
rows = [
    ('TPMS calibration', '10-74', '01/01'),
    ('Temperature correction', '10-74', '03/13 03/14'),
    ('Trip-A reset', '10-74', '03/18'),
    ('Trip-B reset', '10-74', '03/19'),
    ('Alarm volume', '10-74', '03/1D'),
    ('Economy illumination', '10-74', '03/28'),
    ('Navigation prompts', '10-74', '03/38 03/3B 03/48'),
    ('Message notifications', '10-74', '03/40'),
    ('Tachometer', '10-74', '03/5C'),
    ('Remote-start enable', '10-75', '05/04 05/14'),
    ('Passive-entry doors', '10-75', '05/15 05/16'),
    ('Keyless-beep volume', '10-75', '05/17 05/18'),
    ('Keyless flashes', '10-75', '05/19 05/1A'),
    ('Keyless beeps', '10-75', '05/1B 05/1C'),
    ('Interior-light delay', '10-75', '06/04'),
    ('Headlight delay', '10-75', '06/05 06/06'),
    ('Headlight sensitivity', '10-75', '06/07 06/08 06/09 06/0B 06/13'),
    ('Interior-illumination sensitivity', '10-75', '06/0A 06/0C 06/14'),
    ('Wiper-linked headlights', '10-75', '06/15 06/16 08/22'),
    ('Automatic locking', '10-76', '07/0F 07/10 07/11'),
    ('Automatic unlocking', '10-76', '07/12 07/13 07/14 07/15 07/16 07/17 07/18 07/19 07/1A 07/1B 07/1C'),
    ('Remote-unlock doors', '10-76', '07/1D'),
    ('Lock acknowledgement', '10-76', '07/32'),
    ('Relock delay', '10-76', '07/33'),
    ('Walk-away locking', '10-76', '07/30 07/31 07/36'),
    ('Mirror folding', '10-76', '07/37'),
    ('Maintenance reset', '10-76', ''),
    ('Cluster content/layout', '10-11–10-14, 10-71', '03/5B'),
    ('Rear-camera fixed guides', '10-68', ''),
    ('Rear-camera dynamic guides', '10-68', ''),
    ('LaneWatch activation', '10-68', ''),
    ('LaneWatch persistence', '10-68', ''),
    ('LaneWatch guides', '10-68', ''),
    ('Camera defaults', '10-68', ''),
    ('Head-unit language', '10-72', ''),
]
output = []
for name, page, identities in rows:
    candidates = []
    for identity in identities.split():
        raw = catalog[identity]
        candidates.append({k: raw[k] for k in (
            'identity', 'title', 'original_description', 'xml_line', 'data_type',
            'api_to_encoded_values', 'option_labels_from_existing_catalog',
            'unlabeled_api_values', 'range', 'routes', 'route_status')})
    output.append({
        'feature': name,
        'applicability_evidence': 'Honda Civic 2020 Brazil owner manual; equipment-dependent, not every trim',
        'manual_pages': page,
        'candidate_binding_status': 'Semantic match to OEM catalog only; exact Civic variant binding unverified' if candidates else 'Separate OEM app/service path; see audit reports',
        'oem_candidates': candidates,
    })
    if name == 'Cluster content/layout':
        output[-1]['candidate_binding_status'] = 'OEM separate preset selector explicitly uses 03/5B; add/delete/order use ExternalDisplay CustomizeData. Brazil-2020 runtime compatibility unverified.'
        output[-1]['code_report'] = 'instrument-panel-editor.md'
data = {
    'scope': 'OEM Civic vehicle/cluster/camera settings; Brazil 2020 manual cross-check. Not all generations or all infotainment features.',
    'manual': manual_url,
    'retrieval': {'url': mirror_url, 'note': 'Honda-authored manual transcription; official PDF exceeded browser size limit and shell retrieval returned HTTP 403.'},
    'feature_count': len(output),
    'settings': output,
}
(ROOT / 'civic-br-2020-index.json').write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
lines = ['# Civic Brazil 2020: OEM applicability cross-check', '',
    '35 feature concepts documented for Civic; availability varies by equipment. The IDs below are semantic candidates from the supplied OEM catalog, not verified 2020 EXL bindings. Multiple IDs mean alternatives, not extra features. Routes and encoded values are preserved in the JSON; none are OBD PIDs.', '',
    f'Source: [Honda owner manual]({manual_url}); retrieved as a [Honda-authored transcription]({mirror_url}). See the cited printed pages. The official PDF exceeded browser size limits and direct retrieval returned HTTP 403.', '',
    '| Civic feature | Manual page | OEM candidate category/ID |',
    '|---|---|---|']
for row in output:
    ids = ', '.join('`' + c['identity'] + '`' for c in row['oem_candidates']) or 'Separate app/service'
    lines.append(f"| {row['feature']} | {row['manual_pages']} | {ids} |")
lines += ['', 'The Portuguese **cluster** selector `03/0E` is independently proved in OEM code; this manual’s language entry proves the head-unit control, not that same cluster binding. See [language evidence](../stock-language-review.md).', '',
    '[JSON with original-code candidate options and routes](civic-br-2020-index.json). [Instrument app trace](instrument-settings.md). [Vehicle actions](vehicle-settings-and-actions.md). [System/camera code](system-and-camera-settings.md).', '']
(ROOT / 'civic-br-2020-index.md').write_text('\n'.join(lines))
assert len(output) == 35
assert len({r['feature'] for r in output}) == len(output)
print(f'Wrote {len(output)} Civic feature concepts with {sum(len(r["oem_candidates"]) for r in output)} candidate OEM bindings.')
