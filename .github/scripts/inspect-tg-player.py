"""Read-only live contract check. Reports schema and public player code, never tokens or DRM keys."""
import concurrent.futures
import json
import re
import struct
import time
import urllib.error
import urllib.parse
import urllib.request

SITE = "https://livetgtv.lovable.app"
CHANNEL = "872"
UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
MAX = 4 * 1024 * 1024


REPORTS = []


def emit(title, value):
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=True)
    REPORTS.append((title, text[:24000]))


def flush():
    # GitHub truncates notices per step: combine the report, most useful results first.
    def order(item):
        title = item[0]
        return 0 if title == 'TG RPC' else 1 if title.startswith('TG player code') else 2 if title == 'TG page' else 3
    text = '\n\n'.join(title + '\n' + body for title, body in sorted(REPORTS, key=order))[:48000]
    for start in range(0, len(text), 6000):
        data = text[start:start + 6000].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
        print(f"::notice title=TG live inspection {start // 6000 + 1}::{data}", flush=True)


def get(url, rpc=False):
    headers = {'User-Agent': UA, 'Origin': SITE, 'Referer': f'{SITE}/watch/{CHANNEL}',
               'Sec-Fetch-Site': 'same-origin', 'Sec-Fetch-Mode': 'cors', 'Cache-Control': 'no-cache'}
    if rpc:
        headers.update({'x-tsr-serverfn': 'true', 'Accept': 'application/x-tss-framed, application/x-ndjson, application/json'})
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=12) as response:
            data = response.read(MAX + 1)
            if len(data) > MAX:
                raise ValueError('Response too large')
            return response.status, response.headers.get('Content-Type', ''), data
    except urllib.error.HTTPError as error:
        return error.code, '', b''
    except Exception as error:
        return 0, type(error).__name__, b''


def priority(url):
    path = urllib.parse.urlsplit(url).path.lower()
    return 0 if 'servers' in path else 1 if 'player' in path else 2 if ('watch' in path or 'embed' in path) else 3


def scripts(text, base):
    text = text.replace('\\/', '/').replace('\\u002F', '/')
    out = set()
    for _, path in re.findall(r'''(["'`])([^"'`<>\s]+\.m?js(?:\?[^"'`<>\s]*)?)\1''', text):
        if "${" in path:
            continue
        url = urllib.parse.urljoin(SITE + '/' if path.startswith('assets/') else base, path.replace('&amp;', '&'))
        if urllib.parse.urlsplit(url).netloc == urllib.parse.urlsplit(SITE).netloc:
            out.add(url)
    return sorted(out, key=priority)


def functions(text):
    tagged = re.findall(r'_serverFn/([A-Za-z0-9_-]{8,128})(?=[?"\s\x27`]|$)', text)
    called = re.findall(r'''[A-Za-z_$][A-Za-z0-9_$.]*\(\s*(["'`])([a-fA-F0-9]{64})\1''', text)
    meta = re.findall(r'''["']?\bid["']?\s*:\s*(["'`])([a-fA-F0-9]{64})\1''', text)
    return list(dict.fromkeys(tagged + [fn for _, fn in called + meta]))


def safe_url(value):
    parsed = urllib.parse.urlsplit(value)
    host = parsed.hostname or ''
    if parsed.port:
        host += ':' + str(parsed.port)
    path = re.sub(r'[A-Za-z0-9_=+%-]{50,}', '[opaque]', parsed.path)
    return urllib.parse.urlunsplit((parsed.scheme, host, path, '', '')) + (
        '?' + '&'.join(key + '=[redacted]' for key, _ in urllib.parse.parse_qsl(parsed.query)) if parsed.query else '')


def sanitize(value, name='', depth=0):
    if depth > 9:
        return '[depth limit]'
    if name.lower() in {'cookie', 'key', 'kid', 'keyid', 'k', 'clearkeys', 'keys', 'license', 'licenseurl', 'license_url', 'license_key', 'licensekey', 'authorization', 'token', 'epg', 'guide', 'schedule'}:
        return {'present': bool(value), 'type': type(value).__name__, 'length': len(value) if isinstance(value, (str, list, dict)) else 0}
    if isinstance(value, dict):
        return {k: sanitize(v, k, depth + 1) for k, v in list(value.items())[:40]}
    if isinstance(value, list):
        return [sanitize(v, name, depth + 1) for v in value[:8]] + ([f'... {len(value)} total'] if len(value) > 8 else [])
    if isinstance(value, str):
        if value.startswith(('http://', 'https://')):
            return safe_url(value)
        return re.sub(r'\b[0-9a-fA-F]{32,}\b', '[hex redacted]', value[:400])
    return value


def decode(node, refs, depth=0):
    if depth > 48:
        return None
    if isinstance(node, list):
        return [decode(x, refs, depth + 1) for x in node]
    if not isinstance(node, dict):
        return node
    tag = node.get('t')
    if isinstance(tag, dict):
        return decode(tag, refs, depth + 1)
    if not isinstance(tag, int):
        return {k: decode(v, refs, depth + 1) for k, v in node.items()}
    if tag in (0, 1, 3):
        return node.get('s')
    if tag == 2:
        return {2: True, 3: False}.get(node.get('s'))
    if tag == 4:
        return refs.get(node.get('i'))
    if tag == 9:
        value = []
        refs[node.get('i')] = value
        value.extend(decode(x, refs, depth + 1) for x in node.get('a', []))
        return value
    if tag in (10, 11):
        value = {}
        refs[node.get('i')] = value
        props = node.get('p', {})
        for key, child in zip(props.get('k', []), props.get('v', [])):
            value[key] = decode(child, refs, depth + 1)
        return value
    if tag == 21 or (tag == 12 and node.get('s') in (True, 1)):
        return decode(node.get('f'), refs, depth + 1)
    if tag == 23:
        return decode(node.get('a', [None, None])[1], refs, depth + 1)
    return None


def records(body, content_type):
    if content_type.startswith('application/x-tss-framed'):
        position = 0
        while position + 9 <= len(body):
            kind, stream, length = struct.unpack('>BII', body[position:position + 9])
            position += 9
            if position + length > len(body):
                raise ValueError('Truncated frame')
            if kind == 0 and stream == 0:
                yield json.loads(body[position:position + length])
            position += length
    else:
        try:
            yield json.loads(body)
        except json.JSONDecodeError:
            for line in body.splitlines():
                if line.startswith(b'{'):
                    yield json.loads(line)


def inspect():
    page = f'{SITE}/watch/{CHANNEL}?_contract={int(time.time())}'
    status, kind, html = get(page)
    emit('TG page', {'status': status, 'type': kind, 'bytes': len(html)})
    text = html.decode('utf-8', 'replace')
    pending = set(scripts(text, page))
    visited, bodies = set(), {}
    found = {fn: 2 for fn in functions(text)}
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        while pending and len(visited) < 28:
            batch = sorted(pending, key=priority)[:min(4, 28 - len(visited))]
            pending.difference_update(batch)
            visited.update(batch)
            results = list(pool.map(get, batch))
            for url, (code, content_type, body) in zip(batch, results):
                script = body.decode('utf-8', 'replace')
                ids = functions(script)
                emit('TG asset', {'url': safe_url(url), 'status': code, 'bytes': len(body), 'functionIds': ids[:12]})
                for fn in ids:
                    found[fn] = min(found.get(fn, 99), priority(url))
                pending.update(set(scripts(script, url)) - visited)
                if priority(url) < 3:
                    bodies[url] = script
    for url, script in bodies.items():
        parts = []
        for hit in re.finditer(r'getServers|createServerFn|createClientRpc|registerRequestFilter|clearKeys|\.load\(|iframe|setServer|backup|onError|autoSwitch|\.configure\(', script):
            part = script[max(0, hit.start() - 160):hit.end() + 350]
            part = re.sub(r'https?://[^\s"\x27<>`]+', lambda m: safe_url(m.group()), part)
            part = re.sub(r'\b[0-9a-fA-F]{32,}\b', '[hex redacted]', part)
            if part not in parts:
                parts.append(part)
        emit('TG player code ' + urllib.parse.urlsplit(url).path.rsplit('/', 1)[-1], '\n'.join(parts[:22]))
    inner = {'t': 10, 'i': 0, 'p': {'k': ['data'], 'v': [
        {'t': 10, 'i': 1, 'p': {'k': ['id'], 'v': [{'t': 1, 's': CHANNEL}]}, 'o': 0}]}, 'o': 0}
    payload = urllib.parse.quote(json.dumps({'t': inner, 'f': 127, 'm': []}, separators=(',', ':')))
    for fn in sorted(found, key=found.get)[:10]:
        code, kind, body = get(f'{SITE}/_serverFn/{fn}?payload={payload}', rpc=True)
        report = {'function': fn, 'status': code, 'type': kind, 'bytes': len(body)}
        try:
            refs = {}
            report['decoded'] = [sanitize(decode(record, refs)) for record in records(body, kind)]
        except Exception as error:
            report['decodeError'] = type(error).__name__
        emit('TG RPC', report)


if __name__ == '__main__':
    try:
        inspect()
    finally:
        flush()
