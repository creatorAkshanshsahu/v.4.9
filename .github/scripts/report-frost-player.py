"""Expose the deliberately redacted native movie contract checks, never raw response bodies."""
from pathlib import Path
import xml.etree.ElementTree as ET
import json

cases, lines = [], []
for path in Path('app/build/test-results').glob('*/TEST-com.aktv.app.FrostLiveContractTest.xml'):
    root = ET.parse(path).getroot()
    output = [line for node in root.findall('system-out') for line in (node.text or '').splitlines() if line.startswith('FROST_NATIVE ')]
    if not output:
        continue
    lines.extend(output)
    for case in root.findall('testcase'):
        status = 'failed' if case.find('failure') is not None or case.find('error') is not None else 'skipped' if case.find('skipped') is not None else 'passed'
        cases.append({'case': case.get('name'), 'status': status})
text = ('FROST_CASES ' + json.dumps(cases) + '\n' + '\n'.join(dict.fromkeys(lines)))[:18000]
if not lines:
    print('::warning title=Native movie check::No native movie contract report was produced. Do not infer playback success.')
else:
    for index in range(0, len(text), 3000):
        chunk = text[index:index+3000].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
        print(f'::notice title=Native movie check {index//3000+1}::{chunk}')
