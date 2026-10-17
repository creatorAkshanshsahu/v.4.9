"""Expose only the deliberately redacted live-test summaries through GitHub Checks."""
from pathlib import Path
import xml.etree.ElementTree as ET

reports = []
failed = False
for path in Path('app/build/test-results').glob('*/TEST-com.aktv.app.TgLiveContractTest.xml'):
    root = ET.parse(path).getroot()
    failed = failed or root.find('.//failure') is not None or root.find('.//error') is not None
    for output in root.findall('system-out'):
        reports.extend(line for line in (output.text or '').splitlines() if line.startswith('TG_NATIVE '))

text = '\n'.join(dict.fromkeys(reports))[:24000]
if not text:
    print('::warning title=Native TG check::No live native report was produced; do not infer playback success.')
for start in range(0, len(text), 3000):
    escaped = text[start:start + 3000].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print(f'::notice title=Native TG check {start // 3000 + 1}::{escaped}')
if failed:
    print('::warning title=Native TG check::The live resolver contract check failed. Review the redacted results; build success alone is not playback proof.')
