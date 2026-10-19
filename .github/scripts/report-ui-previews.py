"""Bounded native Android test previews, not decoded video or user data. One scene per step."""
from pathlib import Path
import base64
import sys
import xml.etree.ElementTree as ET

name = sys.argv[1]
assert name in ('home-tv', 'home-phone', 'home-movies')
path=Path('app/build/reports/ui-preview')/(name+'.webp')
if name == 'home-tv':
    for report in Path('app/build/test-results/testArm32DebugUnitTest').glob('TEST-com.aktv.app.HomeChromeTest.xml'):
        root=ET.parse(report).getroot()
        lines=[line for node in root.findall('system-out') for line in (node.text or '').splitlines() if line.startswith('UI_GEOMETRY ')]
        for node in root.findall('.//failure'):
            lines.append(node.get('message', '')[:500])
        if lines:
            value='\n'.join(lines).replace('%','%25').replace('\r','%0D').replace('\n','%0A')
            print('::notice title=Native layout metrics::'+value)
if not path.exists():
    print(f'::warning title=Native UI preview::{name} was not produced; no visual assertion is made.')
else:
    encoded=base64.b64encode(path.read_bytes()).decode('ascii')
    if len(encoded)>27000:
        print(f'::warning title=Native UI preview::{name} exceeds the bounded preview transport size ({len(encoded)} chars).')
    else:
        count=(len(encoded)+2999)//3000
        for index in range(count):
            print(f'::notice title=Native UI preview {name} {index+1} of {count}::{encoded[index*3000:(index+1)*3000]}')
