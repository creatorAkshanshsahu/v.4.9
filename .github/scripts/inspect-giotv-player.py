"""Read-only inspection of the user-supplied GioTV/Jtv+ player contract. Never prints credentials.

The sandbox and the TV cannot be trusted to describe the live site, so this probe reads the
advertised player page, its same-origin scripts and the metadata documents they name. It reports
schema, sizes, hosts and token lifetimes only: no cookie, token, key or signed URL is emitted.
"""
import json
import re
import runpy
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

shared = runpy.run_path(str(Path(__file__).with_name('inspect-tg-player.py')))
safe_url = shared['safe_url']

SITE = 'https://giotv-6op.pages.dev'
PAGE = SITE + '/pind?id=816'
UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
MAX = 4 * 1024 * 1024
SAMPLE_IDS = ('816', '872', '1106', '1108', '362', '165')
reports = []
documents = {}


def get(url):
    """Browser-style metadata request: no Referer, exactly like the embedded player."""
    headers = {'User-Agent': UA, 'Cache-Control': 'no-cache, no-store',
               'Accept': 'text/html, application/javascript, application/json, */*'}
    if urllib.parse.urlsplit(url).netloc == urllib.parse.urlsplit(SITE).netloc:
        headers['Origin'] = SITE
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            body = response.read(MAX + 1)
            if len(body) > MAX:
                return 0, '', '', ''
            return response.status, response.headers.get('Content-Type', ''), body.decode('utf-8', 'replace'), \
                response.headers.get('cf-mitigated', '')
    except urllib.error.HTTPError as error:
        return error.code, '', '', error.headers.get('cf-mitigated', '')
    except Exception as error:
        return 0, type(error).__name__, '', ''


# Any static string assignment is reported by NAME only. This matches `const X = "..."` as well as
# nested `Player = { cookie: "..." }` members, so an unfamiliar rename is still discovered.
ASSIGNMENT = re.compile(r"""\b(?:const|let|var)?\s*([A-Za-z_$][\w$]*)\s*[:=]\s*(["'`])([^"'`\\]{4,600})\2""")


TRACKERS = ('google-analytics.com', 'googletagmanager.com', 'doubleclick.net', 'facebook.net',
            'facebook.com', 'sentry.io', 'cloudflareinsights.com', 'hotjar.com', 'mixpanel.com',
            'adservice.google.com', 'googlesyndication.com')


def classify(name):
    """Map an arbitrary property name onto the role the native resolver needs."""
    key = name.lower()
    if ('star' in key or 'sport' in key) and ('cookie' in key or 'token' in key):
        return 'star'
    if 'fallback' in key and ('cookie' in key or 'token' in key):
        return 'fallback'
    if 'cookie' in key or 'token' in key:
        return 'primary'
    if 'channel' in key or key in {'catalog', 'catalogue', 'channels', 'data'}:
        return 'catalogue'
    if 'key' in key:
        return 'keys'
    return None


def endpoints(code, base):
    """Static, non-interpolated assignments that resolve to a same-site or absolute URL."""
    found = {}
    for name, _, value in ASSIGNMENT.findall(code):
        if '${' in value or value.startswith(('data:', '#')):
            continue
        kind = classify(name)
        if kind is None:
            continue
        try:
            resolved = urllib.parse.urljoin(base, value)
        except ValueError:
            continue
        parsed = urllib.parse.urlsplit(resolved)
        if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.fragment:
            continue
        if any(parsed.hostname == host or parsed.hostname.endswith('.' + host) for host in TRACKERS):
            continue
        found.setdefault(kind, {'name': name, 'url': resolved, 'host': parsed.hostname.lower(),
                                'sameSite': parsed.hostname == urllib.parse.urlsplit(SITE).hostname})
    return found


def window(value, clock):
    """Lifetimes only. Never return the credential itself."""
    if not isinstance(value, str) or not value:
        return {'present': False}
    match = re.search(r'__hdnea__=([^&\s;"\'<>]+)', value)
    token = match.group(1) if match else ''
    decoded = urllib.parse.unquote(token)

    def seconds(name):
        hit = re.search(r'(?:^|~)' + name + r'=(\d+)(?:~|$)', decoded)
        return int(hit.group(1)) - clock if hit else None

    expires, starts = seconds('exp'), seconds('st')
    return {'present': bool(token), 'expiresInSeconds': expires, 'startsInSeconds': starts,
            'expired': expires is not None and expires <= 0,
            'notYetValid': starts is not None and starts > 0}


def metadata_valid(kind, status, text):
    if status != 200 or not text.strip():
        return False
    if text.lstrip().startswith('<'):
        return False
    try:
        clean = text.lstrip('\ufeff').strip()
        if kind == 'catalogue' and not clean.startswith('['):
            clean = '[' + clean + ']'
        data = json.loads(clean)
    except (ValueError, TypeError):
        return False
    if kind == 'catalogue':
        entries = data if isinstance(data, list) else [data]
        return any(isinstance(item, dict) and any(field in item for field in ('id', 'channel_id', 'channelId'))
                   for item in entries)
    if kind in ('primary', 'fallback'):
        return isinstance(data, (list, dict))
    return isinstance(data, dict)


def catalogue_report(data):
    """Field shape the native resolver would need: id, url order, headers and keys."""
    entries = data if isinstance(data, list) else [data]
    field_counts, sample = {}, []
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        for key, value in entry.items():
            field_counts[key] = field_counts.get(key, 0) + 1
    for entry in entries:
        if isinstance(entry, dict) and str(entry.get('id')) in SAMPLE_IDS:
            sample.append({
                'id': str(entry.get('id')),
                'idType': type(entry.get('id')).__name__,
                'urlFields': {key: len(str(value)) for key, value in entry.items()
                              if isinstance(value, str) and (key.startswith('url') or key in ('link', 'src'))},
                'hosts': list(dict.fromkeys(safe_url(value) for key, value in entry.items()
                                            if isinstance(value, str) and value.startswith(('http', 'bpk-tv')))),
                'hasKeysArray': isinstance(entry.get('keys'), list),
                'keysEntries': len(entry.get('keys') or []),
                'keyPairShape': sorted((entry.get('keys') or [{}])[0].keys()) if isinstance(entry.get('keys'), list) and entry.get('keys') else [],
                'hasLegacyKey': 'keyId' in entry or 'key' in entry,
            })
    return {'channelCount': len(entries), 'fields': dict(sorted(field_counts.items())[:24]),
            'sample': sample}


def inspect():
    status, kind, html, mitigated = get(PAGE)
    reports.append({'step': 'player page', 'url': safe_url(PAGE), 'status': status, 'type': kind,
                    'bytes': len(html), 'challengeHeader': mitigated or None,
                    'hasBraveShieldText': 'Brave Shields' in html, 'hasPind': '/pind' in html,
                    'hasNavBrave': 'navigator.brave' in html})
    if not html:
        return
    scripts = [urllib.parse.urljoin(PAGE, src) for src in
               re.findall(r'''<script[^>]+src=["']([^"']+)["']''', html, re.I)]
    reports.append({'step': 'script locations',
                    'scripts': [safe_url(script) for script in scripts[:12]]})
    pending, visited, found = [], set(), {}
    codes = [('inline', html)]
    for script in scripts[:8]:
        script_status, _, code, _ = get(script)
        codes.append((safe_url(script), code if script_status == 200 else ''))
    # Lazy chunks referenced by the entry modules are followed one level only.
    for name, code in codes:
        if not code:
            continue
        found.update({key: value for key, value in endpoints(code, PAGE).items() if key not in found})
        reports.append({'step': 'script', 'name': name, 'bytes': len(code),
                        'signals': [signal for signal in
                                    ('__hdnea__', 'bpk-tv', 'jiotvpllive', 'jiotvmblive', 'clearkeys',
                                     'shaka', 'hls.js', 'dash.js', 'jwplayer', 'atob(', 'navigator.brave',
                                     'fetch(', 'localStorage', 'allinonereborn')
                                    if signal in code]})
        for chunk in re.findall(r'''["'`]([^"'`\s]+\.m?js(?:\?[^"'`\s]*)?)["'`]''', code)[:6]:
            resolved = urllib.parse.urljoin(PAGE, chunk)
            if resolved not in visited and resolved.startswith(SITE):
                visited.add(resolved)
                pending.append(resolved)
    reports.append({'step': 'advertised endpoints',
                    'endpoints': {key: {'name': value['name'], 'url': safe_url(value['url'])}
                                  for key, value in found.items()}})
    clock = int(time.time())
    for key, endpoint in found.items():
        data_status, data_kind, text, _ = get(endpoint['url'])
        report = {'step': 'metadata', 'kind': key, 'name': endpoint['name'],
                  'url': safe_url(endpoint['url']), 'status': data_status, 'type': data_kind,
                  'bytes': len(text), 'schemaAccepted': metadata_valid(key, data_status, text)}
        if report['schemaAccepted']:
            documents[key] = text
            data = json.loads(text.lstrip('\ufeff').strip() if not text.lstrip().startswith('[')
                              else text.lstrip('\ufeff').strip())
            if key == 'catalogue':
                report.update(catalogue_report(data))
            elif key in ('primary', 'fallback'):
                entries = data if isinstance(data, list) else [data]
                cookies = [item.get('cookie') for item in entries if isinstance(item, dict)]
                report['records'] = len(entries)
                report['recordsWithCookie'] = sum(bool(value) for value in cookies)
                report['lifetimes'] = [window(value, clock) for value in cookies if window(value, clock)['present']][:4]
                report['fields'] = sorted({key for item in entries if isinstance(item, dict) for key in item})[:16]
            elif key == 'star':
                entries = (data.get('successful_results') or []) + (data.get('failed_results') or [])
                report['records'] = len(entries)
                report['fields'] = sorted({key for item in entries if isinstance(item, dict) for key in item})[:16]
                report['sampleChannels'] = [str(item.get('channel_id')) for item in entries[:6] if isinstance(item, dict)]
                report['errorDetailsKeys'] = sorted({key for item in entries if isinstance(item, dict)
                                                     for key in ((item.get('error_details') or {}).keys())})[:12]
            elif key == 'keys':
                report['fields'] = sorted(data.keys())[:16] if isinstance(data, dict) else 'array'
        reports.append(report)
    for chunk in pending[:4]:
        chunk_status, _, code, _ = get(chunk)
        reports.append({'step': 'lazy chunk', 'url': safe_url(chunk), 'status': chunk_status, 'bytes': len(code)})


def availability():
    """Exact-channel comparison on the assets only; no credential contents leave this process."""
    if 'catalogue' not in documents:
        reports.append({'step': 'availability', 'skipped': 'no catalogue document'})
        return
    try:
        catalogue = json.loads(documents['catalogue'].lstrip('\ufeff').strip()) if documents['catalogue'].lstrip().startswith('[') \
            else json.loads('[' + documents['catalogue'].lstrip('\ufeff').strip() + ']')
    except (ValueError, TypeError) as error:
        reports.append({'step': 'availability', 'skipped': type(error).__name__})
        return
    wanted = ('872', '1108', '165')
    for channel in wanted:
        entry = next((item for item in catalogue if isinstance(item, dict) and str(item.get('id')) == channel), None)
        reports.append({'step': 'availability', 'channel': channel, 'present': entry is not None,
                        'urlHosts': list(dict.fromkeys(
                            safe_url(entry.get(key)) for key in ('url', 'url1', 'url2', 'url3')
                            if entry and isinstance(entry.get(key), str))) if entry else []})


if __name__ == '__main__':
    try:
        inspect()
        availability()
    finally:
        text = '\n\n'.join(json.dumps(report, ensure_ascii=True) for report in reports)[:24000]
        for start in range(0, len(text), 3000):
            data = text[start:start + 3000].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
            print(f'::notice title=GioTV live inspection {start // 3000 + 1}::{data}')
