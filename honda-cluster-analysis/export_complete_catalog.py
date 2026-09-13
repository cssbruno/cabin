"""Export every stock Honda configuration entry. No vehicle or network access."""
import csv
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

BASE = Path(__file__).resolve().parent
source = BASE / 'vehicle_customize_config.xml'
labels_source = BASE.parent / 'honda-customizer/app/src/main/assets/catalog.json'
text = source.read_text()
labels = {(r['category'], r['id']): r for r in json.loads(labels_source.read_text())}
entries = []
for match in re.finditer(r'<Item\s[^>]*>.*?</Item>', text, re.S):
    xml = ET.fromstring(match[0])
    category, setting_id = (int(xml.findtext(k), 0) for k in ('Category', 'Id'))
    label = labels[(category, setting_id)]
    values = {int(x.get('key'), 0): int(x.text, 0) for x in xml.findall('DataList/entry')}
    assert {int(k): v for k, v in label['values'].items()} == values
    assert label['type'] == int(xml.findtext('DataType'), 0)
    routes = [{'source': int(x.get('key'), 0), 'menu': int(x.text, 0)} for x in xml.findall('InfoFrom/entry')]
    assert bool(routes) == label['hasRoute']
    comment = re.search(r'<!--(.*?)-->', match[0], re.S)
    names = label.get('labels', {})
    option_names = names.get('(default)', {}).get('options', [])
    named_values = {v: option_names[v - 1] for v in values if 0 < v <= len(option_names) and option_names[v - 1]} if label['type'] == 0 else {}
    entries.append({
        'category': category, 'id': setting_id,
        'identity': f'{category:02X}/{setting_id:02X}',
        'xml_line': text.count('\n', 0, match.start()) + 1,
        'original_description': comment[1].strip() if comment else '',
        'title': names.get('(default)', {}).get('title'),
        'title_pt': names.get('pt', {}).get('title'),
        'data_type': label['type'], 'api_to_encoded_values': values,
        'option_labels_from_existing_catalog': names,
        'named_api_values_from_existing_catalog': named_values,
        'unlabeled_api_values': [v for v in values if v not in named_values] if label['type'] == 0 else [],
        'range': [int(x.text, 0) for x in xml.findall('DataRange/entry')],
        'routes': routes,
        'route_status': 'metadata_present' if routes else 'no_xml_route',
        'fyt_mapping': 'not_established_by_stock_catalog',
        'vehicle_support': 'not_live_verified',
    })
assert len(entries) == len(labels) == 313
assert len({r['identity'] for r in entries}) == 313
languages = [r for r in entries if '言語' in r['original_description']]
summary = {
    'source': source.name, 'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
    'label_source': str(labels_source), 'label_source_sha256': hashlib.sha256(labels_source.read_bytes()).hexdigest(),
    'entries': len(entries), 'with_xml_route': sum(bool(r['routes']) for r in entries),
    'language_entries': len(languages), 'language_entries_with_route': sum(bool(r['routes']) for r in languages),
    'named_titles': sum(bool(r['title']) for r in entries),
    'entries_with_unlabeled_api_values': sum(bool(r['unlabeled_api_values']) for r in entries),
    'scope': 'Complete entry inventory of this one XML, not all Honda firmware capabilities or live support.',
    'label_provenance': 'Existing original-head-unit client catalog; original XML does not supply English option labels.',
}
(BASE / 'complete-settings-catalog.json').write_text(json.dumps({'summary': summary, 'settings': entries}, ensure_ascii=False, indent=2) + '\n')
with (BASE / 'complete-settings-catalog.csv').open('w', newline='', encoding='utf-8-sig') as f:
    writer = csv.writer(f)
    writer.writerow(['category/id','title','title_pt','original_description','xml_line','data_type','api_to_encoded_values','routes','status'])
    for r in entries:
        writer.writerow([r['identity'],r['title'],r['title_pt'],r['original_description'],r['xml_line'],r['data_type'],json.dumps(r['api_to_encoded_values']),json.dumps(r['routes']),r['route_status']])
lines = ['# Complete stock Honda settings inventory', '',
    'Every one of the 313 category/ID pairs in the reviewed XML is included. Variant entries are retained separately. Route metadata is not proof of vehicle support or a FYT command.', '',
    'English/Portuguese labels come from the existing Honda client catalog; untranslated entries retain the original source description. The JSON preserves all options, encodings, ranges and routes.', '',
    '| ID | Setting | Portuguese label | XML line | Route metadata |', '|---|---|---|---:|---|']
for r in entries:
    title = (r['title'] or r['original_description']).replace('|','\\|')
    pt = (r['title_pt'] or '').replace('|','\\|')
    routes = ', '.join(f"{v['source']:02X}/{v['menu']:02X}" for v in r['routes']) or 'None'
    lines.append(f"| {r['identity']} | {title} | {pt} | {r['xml_line']} | {routes} |")
(BASE / 'complete-settings-catalog.md').write_text('\n'.join(lines)+'\n')
print(json.dumps(summary, ensure_ascii=False, indent=2))
