"""Inspect the user-provided JJTV player contract without executing or publishing its credentials."""
import concurrent.futures
import base64
import json
import time
from email.utils import parsedate_to_datetime
from html.parser import HTMLParser
from pathlib import Path
import re
import runpy
import urllib.error
import urllib.parse
import urllib.request

shared = runpy.run_path(str(Path(__file__).with_name('inspect-tg-player.py')))
def safe_url(value):
    try:
        return shared['safe_url'](value)
    except ValueError:
        return '[dynamic URL]'

SITE = 'https://jjtvxweb.pages.dev'
PAGE = SITE + '/pind?id=816'
reports = []
endpoints = {}
http_times = {}
data_bodies = {}
data_status = {}


class Scripts(HTMLParser):
    def __init__(self):
        super().__init__()
        self.external, self.inline, self.current = [], [], None

    def handle_starttag(self, tag, attrs):
        if tag == 'script':
            src = dict(attrs).get('src')
            if src:
                self.external.append(src)
            self.current = []

    def handle_data(self, data):
        if self.current is not None:
            self.current.append(data)

    def handle_endtag(self, tag):
        if tag == 'script' and self.current is not None:
            self.inline.append(''.join(self.current))
            self.current = None


def get(url, browser_style=False):
    headers = {
        'User-Agent': shared['UA'], 'Origin': SITE, 'Referer': PAGE,
        'Cache-Control': 'no-cache, no-store', 'Accept': 'text/html, application/javascript, application/json'}
    if browser_style:
        headers.pop('Referer', None)
        if urllib.parse.urlsplit(url).netloc == urllib.parse.urlsplit(SITE).netloc:
            headers.pop('Origin', None)
        headers['Accept'] = '*/*'
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=12) as response:
            try:
                date = parsedate_to_datetime(response.headers.get('Date', '')).timestamp()
                age = max(0, int(response.headers.get('Age', '0')))
                http_times[url] = {'serverEpochSeconds': int(date + age), 'ageSeconds': age}
            except (ValueError, TypeError, OverflowError):
                http_times[url] = {}
            body = response.read(4 * 1024 * 1024 + 1)
            if len(body) > 4 * 1024 * 1024:
                return 0, 'too-large', ''
            return response.status, response.headers.get('Content-Type', ''), body.decode('utf-8', 'replace')
    except urllib.error.HTTPError as error:
        return error.code, '', ''
    except Exception as error:
        return 0, type(error).__name__, ''


def redact(code):
    code = re.sub(r'https?://[^\s"\x27<>`\\]+', lambda m: safe_url(m.group()), code)
    code = re.sub(r'\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b', '[JWT redacted]', code)
    code = re.sub(r'data:[^\s"\x27<>`]+', '[data URI redacted]', code)
    code = re.sub(r'\b[0-9a-fA-F]{32,}\b', '[hex redacted]', code)
    code = re.sub(r'''(["'`])[A-Za-z0-9_+/=-]{20,}\1''', '"[opaque literal redacted]"', code)
    code = re.sub(r'''(["'`])[^"'`]*__hdnea__=[^"'`]*[~][^"'`]*\1''', '"[token redacted]"', code)
    code = re.sub(r'''(\b(?:authorization|cookie|token|key|kid|keyid|key_id|secret)\b["']?\s*[:=]\s*)(["'`])[^"'`]*\2''', r'\1"[credential redacted]"', code, flags=re.I)
    return code


def code_report(name, code):
    for key, quote, value in re.findall(r'''\b(?:const|let|var)\s+(CHANNELS_URL|COOKIE_PRIMARY|COOKIE_FALLBACK|COOKIE_STAR_SPORTS)\s*=\s*(["'`])([^"'`]*)\2''', code):
        if value and '${' not in value:
            endpoints[key] = urllib.parse.urljoin(PAGE, value)
    if 'function extractStarCookie' in code:
        start = code.index('function extractStarCookie')
        end = code.find('function isJioPlLiveUrl', start)
        reports.append('Full Star-cookie selector\n' + redact(code[start:end if end > start else start + 2500]))
    imports = re.findall(r'''["'`]([^"'`\s]+\.m?js(?:\?[^"'`\s]*)?)["'`]''', code)
    urls = list(dict.fromkeys(safe_url(m) for m in re.findall(r'https?://[^\s"\x27<>`\\]+', code)))
    reports.append(name + '\n' + str({'bytes': len(code), 'urls': urls[:30]}))
    excerpts, end = [], 0
    for hit in re.finditer(r'fetch\(|XMLHttpRequest|\.open\(|\.load\(|\.configure\(|clearKeys|license|cookie|manifest|streamUrl|new URL|\.setup\(|registerRequestFilter|atob|JSON\.parse', code, re.I):
        if hit.start() < end:
            continue
        start, end = max(0, hit.start() - 180), min(len(code), hit.end() + 600)
        excerpts.append(redact(code[start:end]))
        if len(excerpts) >= 16:
            break
    reports.extend(excerpts)
    return imports


def inspect():
    pending, visited = set(), set()
    for url in (PAGE, SITE + '/'):
        status, kind, html = get(url)
        reports.append(str({'page': safe_url(url), 'status': status, 'type': kind, 'bytes': len(html)}))
        parser = Scripts()
        parser.feed(html)
        reports.append('Script locations: ' + str([safe_url(urllib.parse.urljoin(url, item)) for item in parser.external]))
        for index, code in enumerate(parser.inline):
            pending.update(urllib.parse.urljoin(url, path) for path in code_report('Inline script ' + str(index), code))
        pending.update(urllib.parse.urljoin(url, path) for path in parser.external)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        while pending and len(visited) < 10:
            batch = sorted(url for url in pending if urllib.parse.urlsplit(url).netloc == urllib.parse.urlsplit(SITE).netloc)[:4]
            if not batch:
                break
            pending.difference_update(batch)
            visited.update(batch)
            for url, (status, kind, code) in zip(batch, pool.map(get, batch)):
                reports.append(str({'script': safe_url(url), 'status': status, 'type': kind}))
                pending.update(urllib.parse.urljoin(url, path) for path in code_report('Script ' + safe_url(url), code))
            pending.difference_update(visited)


def token_health(text, clock):
    """Only lifetimes/counts. Never return signatures, cookies or raw signed URLs."""
    values = re.findall(r'__hdnea__=([^&\s;"\x27<>]+)', text)
    expiries = []
    for value in set(values):
        match = re.search(r'(?:^|~)exp=(\d+)(?:~|$)', urllib.parse.unquote(value))
        if match:
            expiries.append(int(match.group(1)) - clock)
    return {'distinctTokens': len(set(values)), 'knownExpiry': len(expiries),
            'expired': sum(value <= 0 for value in expiries),
            'nearExpiry30s': sum(0 < value <= 30 for value in expiries),
            'minimumSecondsLeft': min(expiries, default=None), 'maximumSecondsLeft': max(expiries, default=None)}


def metadata_valid(name, status, text):
    if status != 200:
        return False
    try:
        clean = text.lstrip('\ufeff').strip()
        if name == 'CHANNELS_URL' and not clean.startswith('['):
            clean = '[' + clean + ']'
        data = json.loads(clean)
        if name == 'COOKIE_STAR_SPORTS':
            return isinstance(data, dict) and any(isinstance(data.get(key), list) for key in ('successful_results', 'failed_results'))
        if name in ('COOKIE_PRIMARY', 'CHANNELS_URL'):
            return isinstance(data, list)
        return isinstance(data, dict)
    except (ValueError, TypeError):
        return False


def inspect_data():
    for name, url in endpoints.items():
        status, kind, text = get(url)
        browser_status, browser_kind, browser_text = get(url, browser_style=True)
        canonical_valid = metadata_valid(name, browser_status, browser_text)
        legacy_valid = metadata_valid(name, status, text)
        data_status[name] = {'canonicalStatus': browser_status, 'canonicalType': browser_kind,
                             'schemaAccepted': canonical_valid, 'legacyStatus': status,
                             'sampleProfile': 'canonical' if canonical_valid else 'legacy' if legacy_valid else 'unavailable'}
        if canonical_valid:
            data_bodies[name] = browser_text
        elif legacy_valid:
            data_bodies[name] = text
        report = {'endpoint': name, 'url': safe_url(url), 'status': status, 'type': kind, 'bytes': len(text),
                  'canonicalNoReferrer': {'status': browser_status, 'type': browser_kind, 'bytes': len(browser_text)},
                  'httpTime': http_times.get(url, {})}
        if name.startswith('COOKIE_'):
            reference = http_times.get(url, {}).get('serverEpochSeconds', int(time.time()))
            report['tokenHealth'] = token_health(text, reference) if legacy_valid else None
            # Only public metadata endpoints, never append a cache-buster to a signed media URL.
            if not urllib.parse.urlsplit(url).query:
                fresh_url = url + '?_aktv_probe=' + str(time.time_ns())
                fresh_status, _, fresh_text = get(fresh_url)
                fresh_reference = http_times.get(fresh_url, {}).get('serverEpochSeconds', int(time.time()))
                report['uncachedMetadata'] = {'status': fresh_status, 'bodyChanged': (fresh_text != text) if legacy_valid and metadata_valid(name, fresh_status, fresh_text) else None,
                    'httpTime': http_times.get(fresh_url, {}), 'tokenHealth': token_health(fresh_text, fresh_reference) if metadata_valid(name, fresh_status, fresh_text) else None}
        try:
            clean = text.lstrip('\ufeff').strip()
            if name == 'CHANNELS_URL' and not clean.startswith('['):
                clean = '[' + clean + ']'
            data = json.loads(clean)
            report['schema'] = list(data.keys()) if isinstance(data, dict) else 'array'
            if name == 'CHANNELS_URL':
                report['channelCount'] = len(data)
                selected = [entry for entry in data if str(entry.get('id')) in ('165', '816', '872', '1106', '1108', '362')]
                report['selected'] = shared['sanitize'](selected)
                report['keyFormats'] = {str(entry.get('id')): [
                    {key: len(str(value)) for key, value in pair.items()}
                    for pair in entry.get('keys', [])[:4] if isinstance(pair, dict)
                ] for entry in selected}
                def canonical(value):
                    if not isinstance(value, str):
                        return None
                    raw = value.strip().removeprefix('0x').removeprefix('0X').replace('-', '').replace(' ', '')
                    if re.fullmatch(r'[0-9a-fA-F]{32}', raw):
                        return bytes.fromhex(raw)
                    try:
                        decoded = base64.urlsafe_b64decode(value + '=' * (-len(value) % 4))
                        return decoded if len(decoded) == 16 else None
                    except (ValueError, TypeError):
                        return None
                analyses = {}
                for entry in selected:
                    pairs = entry.get('keys') or [{'keyId': entry.get('keyId'), 'key': entry.get('key')}]
                    groups, invalid = {}, 0
                    for pair in pairs:
                        kid, key = canonical(pair.get('keyId')), canonical(pair.get('key'))
                        if kid is None or key is None:
                            invalid += 1
                        else:
                            groups.setdefault(kid, set()).add(key)
                    analyses[str(entry.get('id'))] = {'entries': len(pairs), 'distinctValidIds': len(groups),
                        'invalidPairs': invalid, 'idsWithConflictingValues': sum(len(values) > 1 for values in groups.values())}
                report['keyAnalysis'] = analyses
            elif name == 'COOKIE_STAR_SPORTS':
                entries = (data.get('successful_results') or []) + (data.get('failed_results') or [])
                report['resultCount'] = len(entries)
                selected = [entry for entry in entries if str(entry.get('channel_id')) in ('165', '816', '872', '1106', '1108', '362')]
                report['selected'] = shared['sanitize'](selected)
            else:
                report['data'] = shared['sanitize'](data[:2] if isinstance(data, list) else data)
        except Exception as error:
            report['decodeError'] = type(error).__name__
        # Results come before long code excerpts to fit the annotation budget.
        reports.insert(0, 'JJTV data\n' + json.dumps(report, ensure_ascii=True))


def credential(value):
    if not isinstance(value, str):
        return ''
    match = re.search(r'__hdnea__=([^&\s;"\x27<>]+)', value)
    return match.group(1) if match else ''


def window(token, clock):
    decoded = urllib.parse.unquote(token)
    def seconds(name):
        match = re.search(r'(?:^|~)' + name + r'=(\d+)(?:~|$)', decoded)
        return int(match.group(1)) - clock if match else None
    expires, starts = seconds('exp'), seconds('st')
    return {'present': bool(token), 'expiresInSeconds': expires, 'startsInSeconds': starts,
            'expired': expires is not None and expires <= 0, 'notYetValid': starts is not None and starts > 0}


def pick_token(values, clock, respect_start=False):
    def score(value):
        data = window(value, clock)
        rank = 0 if (data['expiresInSeconds'] is not None and data['expiresInSeconds'] <= 10) or (respect_start and data['notYetValid']) else 1 if data['expiresInSeconds'] is None else 2
        return rank, data['expiresInSeconds'] if data['expiresInSeconds'] is not None else -10**20
    return max((value for value in values if value), key=score, default='')


def comparison_probe(url):
    # Only the provider's advertised Jio manifests; no invented proxy, account auth or alternate host.
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or parsed.hostname not in {'jiotvmblive.cdn.jio.com', 'jiotvpllive.cdn.jio.com'} or parsed.username:
        return {'skipped': 'not-an-advertised-Jio-manifest'}
    request = urllib.request.Request(url, headers={'User-Agent': shared['UA'], 'Origin': SITE,
        'Cache-Control': 'no-cache', 'Range': 'bytes=0-8191'})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            prefix = response.read(8192)
            return {'status': response.status, 'prefixBytes': len(prefix),
                    'dashSignature': b'<MPD' in prefix, 'hlsSignature': prefix.lstrip().startswith(b'#EXTM3U')}
    except urllib.error.HTTPError as error:
        return {'status': error.code}
    except Exception as error:
        return {'errorClass': type(error).__name__}


def inspect_availability():
    """Compare the working control and failing exact channels, never credentials or token hashes."""
    try:
        body = data_bodies.get('CHANNELS_URL', '').lstrip('\ufeff').strip()
        catalog = json.loads(body if body.startswith('[') else '[' + body + ']')
        primary = json.loads(data_bodies.get('COOKIE_PRIMARY', '[]'))
        star = json.loads(data_bodies.get('COOKIE_STAR_SPORTS', '{}'))
        clock = int(time.time())
        primary_token = pick_token([credential(entry.get('cookie')) for entry in primary if isinstance(entry, dict)], clock)
        rows = []
        for channel in ('165', '872', '1106', '1108', '362'):
            entry = next((entry for entry in catalog if str(entry.get('id')) == channel), None)
            if entry is None:
                rows.append({'channel': channel, 'cataloguePresent': False}); continue
            url = entry.get('url', '').replace('bpk-tv//', 'bpk-tv/', 1)
            parsed = urllib.parse.urlsplit(url)
            is_star = parsed.hostname == 'jiotvpllive.cdn.jio.com'
            entries = (star.get('successful_results') or []) + (star.get('failed_results') or [])
            values = [credential((item.get('error_details') or {}).get('final_url') or item.get('final_url'))
                      for item in entries if str(item.get('channel_id')) == channel]
            selected = pick_token(values, clock) if is_star else primary_token
            original = credential(url)
            cookie_endpoint = 'COOKIE_STAR_SPORTS' if is_star else 'COOKIE_PRIMARY'
            lookup_available = cookie_endpoint in data_bodies
            report = {'channel': channel, 'cataloguePresent': True, 'family': 'PL' if is_star else 'general',
                      'credentialLookup': data_status.get(cookie_endpoint, {'sampleProfile': 'unavailable'}),
                      'selected': window(selected, clock) if lookup_available else None, 'advertisedUrl': window(original, clock),
                      'sameCredential': bool(selected) and selected == original,
                      'usesGeneralCookie': bool(selected) and selected == primary_token}
            if channel in ('165', '872', '1108'):
                if selected and not window(selected, clock)['expired'] and not window(selected, clock)['notYetValid']:
                    query = [part for part in parsed.query.split('&') if part and part.split('=', 1)[0] != '__hdnea__']
                    query.append('__hdnea__=' + selected)
                    supplied = urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, parsed.path, '&'.join(query), ''))
                    report['selectedProbe'] = comparison_probe(supplied)
                if original and original != selected and not window(original, clock)['expired'] and not window(original, clock)['notYetValid']:
                    report['advertisedUrlProbe'] = comparison_probe(url)
            rows.append(report)
        reports.insert(0, 'JJTV exact-channel availability\n' + json.dumps({'inspectedUtcEpoch': clock,
            'devicePlaybackTested': False, 'channels': rows}))
    except Exception as error:
        reports.insert(0, 'JJTV availability comparison incomplete: ' + type(error).__name__)


if __name__ == '__main__':
    try:
        inspect()
        inspect_data()
        inspect_availability()
    finally:
        text = '\n\n'.join(reports)[:24000]
        for start in range(0, len(text), 3000):
            data = text[start:start + 3000].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
            print(f'::notice title=JJTV live inspection {start // 3000 + 1}::{data}')
