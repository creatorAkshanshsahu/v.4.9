"""Summarize deterministic JUnit results before the opt-in tests replace the arm64 result files."""
from pathlib import Path
import json
import xml.etree.ElementTree as ET

for flavor in ('Arm32', 'Arm64'):
    files = list(Path(f'app/build/test-results/test{flavor}DebugUnitTest').glob('TEST-*.xml'))
    result = {'abi': flavor.lower(), 'tests': 0, 'passed': 0, 'skipped': 0, 'failures': 0, 'errors': 0}
    for path in files:
        root = ET.parse(path).getroot()
        for field in ('tests', 'skipped', 'failures', 'errors'):
            result[field] += int(root.get(field, '0'))
    result['passed'] = result['tests'] - result['skipped'] - result['failures'] - result['errors']
    if not files:
        print(f'::warning title=Offline tests {flavor}::No deterministic JUnit results were produced.')
    else:
        message = json.dumps(result).replace('%', '%25')
        print(f'::notice title=Offline tests {flavor}::{message}')
