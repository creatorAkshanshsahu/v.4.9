"""Expose only the deliberately redacted live-test summaries through GitHub Checks."""
from pathlib import Path
import xml.etree.ElementTree as ET
import json

reports = []
failed = False
cases = []
for path in Path('app/build/test-results').glob('*/TEST-com.aktv.app.TgLiveContractTest.xml'):
    root = ET.parse(path).getroot()
    failed = failed or root.find('.//failure') is not None or root.find('.//error') is not None
    output_lines = [line for output in root.findall('system-out') for line in (output.text or '').splitlines() if line.startswith('TG_NATIVE ')]
    reports.extend(output_lines)
    if output_lines or root.find('.//failure') is not None or root.find('.//error') is not None:
        for case in root.findall('testcase'):
            status = 'failed' if case.find('failure') is not None or case.find('error') is not None else 'skipped' if case.find('skipped') is not None else 'passed'
            cases.append({'case': case.get('name', ''), 'status': status})

text = ('LIVE_CASES ' + json.dumps(cases) + '\n' if cases else '') + '\n'.join(dict.fromkeys(reports))
text = text[:24000]
if not text:
    print('::warning title=Native live check::No live native report was produced; do not infer playback success.')
for start in range(0, len(text), 3000):
    escaped = text[start:start + 3000].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print(f'::notice title=Native live check {start // 3000 + 1}::{escaped}')
if failed:
    print('::warning title=Native live check::The live resolver contract check failed. Review the redacted results; build success alone is not playback proof.')

expired = {}
for line in reports:
    try:
        data = json.loads(line[len('TG_NATIVE '):])
        if data.get('credentialIssue') == 'EXPIRED' or any(source.get('tokenSecondsLeft', 1) <= 0 for source in data.get('sources', [])):
            expired.setdefault(data.get('provider', 'unknown'), set()).add(data.get('channel', 'unknown'))
    except (ValueError, TypeError):
        pass
if expired:
    summary = '; '.join(provider + ': ' + ', '.join(sorted(ids)) for provider, ids in expired.items())
    print('::warning title=Upstream expired credentials::Some advertised tokens were expired: ' + summary + '. Metadata checks do not establish playback; a current token must be supplied by the provider.')
