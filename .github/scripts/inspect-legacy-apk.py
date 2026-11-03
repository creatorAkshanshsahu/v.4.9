"""Read-only inspection of the user's public 4.7.3 APK. Never install/run it or print credentials.

Only a bounded public download, ZIP/manifest metadata, method references and redacted routing
instructions are inspected. The APK remains in RUNNER_TEMP; only this redacted report may be saved.
"""
from html.parser import HTMLParser
from pathlib import Path
import contextlib
import hashlib
import io
import json
import logging
import os
import re
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile

FILE_ID = '1aB-lOlckOyyBnAuLe8hl769puc8S4mXN'
START = 'https://drive.usercontent.google.com/download?' + urllib.parse.urlencode({'id': FILE_ID, 'export': 'download'})
HOSTS = {'drive.google.com', 'drive.usercontent.google.com', 'docs.google.com'}
MAX_APK = 110 * 1024 * 1024
MAX_DEX = 64 * 1024 * 1024
PACKAGE = 'com.akshansh.aktv'
APP = 'Lcom/aktv/app/'
OWNERS = {'MainActivity', 'Streams', 'TgServers', 'StreamProfile'}
ROUTING = {'play', 'wantsTg', 'setEngine', 'startEmbed', 'openEmbed', 'fallbackToTg', 'fallbackAlt',
           'switchEngine', 'resolveAndStart', 'startV1', 'startV2', 'nextRoute', 'nextSource',
           'onStreamError', 'buildRoutes', 'factory', 'jarFor', 'fetch', 'scrapeAlt'}
SAFE_STRINGS = {'native', 'tg', 'alt', 'eng_', 'p_', 'browser', 'player', 'site', 'site+token',
                'jiotv', 'jiotv+token', 'exo', 'browser+token', 'Referer', 'Origin', 'Cookie',
                'User-Agent', 'Accept', 'Cache-Control', '__hdnea__', '__hdnea__='}


def allowed(raw):
    try:
        url = urllib.parse.urlsplit(raw)
        return url.scheme == 'https' and url.hostname in HOSTS and url.username is None and url.password is None
    except ValueError:
        return False


class PublicRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not allowed(newurl):
            raise ValueError('Unapproved download redirect')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class DownloadForm(HTMLParser):
    def __init__(self):
        super().__init__()
        self.action = None
        self.fields = {}
        self.active = False

    def handle_starttag(self, tag, attrs):
        data = dict(attrs)
        if tag == 'form' and data.get('id') == 'download-form':
            self.active = True
            self.action = data.get('action')
        elif self.active and tag == 'input' and data.get('type') == 'hidden':
            name = data.get('name')
            if name in {'id', 'export', 'confirm', 'uuid', 'at'}:
                self.fields[name] = data.get('value', '')

    def handle_endtag(self, tag):
        if tag == 'form':
            self.active = False

    def target(self, base):
        if not self.action or self.fields.get('id') != FILE_ID:
            raise ValueError('No public download confirmation for the requested APK')
        target = urllib.parse.urljoin(base, self.action)
        if not allowed(target):
            raise ValueError('Unapproved confirmation action')
        return target.split('?')[0] + '?' + urllib.parse.urlencode(self.fields)


def download(path):
    opener = urllib.request.build_opener(PublicRedirects(), urllib.request.HTTPCookieProcessor())
    url = START
    for _ in range(3):
        if not allowed(url):
            raise ValueError('Unapproved APK destination')
        request = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0', 'Accept': '*/*'})
        with opener.open(request, timeout=40) as response:
            prefix = response.read(4096)
            if prefix.startswith(b'PK\x03\x04'):
                size = len(prefix)
                with path.open('wb') as out:
                    out.write(prefix)
                    while True:
                        data = response.read(64 * 1024)
                        if not data:
                            break
                        size += len(data)
                        if size > MAX_APK:
                            raise ValueError('APK exceeds inspection size limit')
                        out.write(data)
                return size
            html = (prefix + response.read(256 * 1024)).decode('utf-8', 'replace')
            # Only the ordinary large-file scan warning. Do not work around private/blocked files.
            if 'too large' not in html.lower() or 'scan' not in html.lower():
                raise ValueError('Public APK download unavailable or not a large-file confirmation')
            form = DownloadForm()
            form.feed(html)
            url = form.target(response.url)
    raise ValueError('Too many download confirmations')


def safe_instruction(name, output, string=None):
    """Never emits arbitrary string/numeric/array literals from a DEX file."""
    if name.startswith('const-string'):
        return name + ' ' + (repr(string) if string in SAFE_STRINGS else '[string literal redacted]')
    if name.startswith('const'):
        match = re.fullmatch(r'(v\d+),\s*(-?\d+)', output)
        if match and int(match[2]) in {-1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 16}:
            return name + ' ' + match[1] + ', ' + match[2]
        return name + ' [literal redacted]'
    if 'array-data' in name or name.endswith('payload'):
        return name + ' [payload omitted]'
    # Register/branch syntax and valid Java field/method descriptors only; no arbitrary DEX text.
    if re.fullmatch(r'[A-Za-z0-9_$<>/;:,.()\[\] +x=\-]*', output) and len(output) <= 300:
        return name + ' ' + output
    return name + ' [operand redacted]'


def routing_methods(dex):
    result = []
    for cls in dex.get_classes():
        owner = cls.get_name()
        if not owner.startswith(APP):
            continue
        short = owner[len(APP):].rstrip(';')
        base = short.split('$')[0]
        if base not in OWNERS:
            continue
        for method in cls.get_methods():
            name = method.get_name()
            # Lambda bodies can own a branch/HTTP request even when the enclosing method only submits it.
            selected = name in ROUTING or any(name.startswith(root + '$') for root in ROUTING)
            if not selected or not method.get_code():
                continue
            instructions, invokes = [], set()
            for instruction in method.get_instructions():
                op = instruction.get_name()
                value = None
                if op.startswith('const-string'):
                    try:
                        value = dex.get_cm_string(instruction.get_ref_kind())
                    except Exception:
                        pass
                output = instruction.get_output()
                safe = safe_instruction(op, output, value)
                if len(instructions) < 230:
                    instructions.append(safe)
                if op.startswith('invoke'):
                    for ref in re.findall(r'L[A-Za-z0-9_$/]+;->[A-Za-z0-9_$<>]+', output):
                        invokes.add(ref)
            result.append({'class': short, 'method': name, 'calls': sorted(invokes), 'instructions': instructions})
    return result


def inspect(path):
    # Silence parser library logs: APK strings can contain a prebundled catalogue/API credentials.
    logging.disable(logging.CRITICAL)
    from loguru import logger
    logger.remove()
    from androguard.core.apk import APK
    from androguard.core.dex import DEX
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) > 20000 or 'AndroidManifest.xml' not in names:
            raise ValueError('Not a bounded Android APK')
        dex_names = [n for n in names if re.fullmatch(r'classes\d*\.dex', n)]
        if not dex_names or sum(archive.getinfo(n).file_size for n in dex_names) > MAX_DEX:
            raise ValueError('DEX data exceeds inspection size limit')
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            apk = APK(str(path), skip_analysis=False)
            package = apk.get_package()
            version = apk.get_androidversion_name()
            code = apk.get_androidversion_code()
            if package != PACKAGE or version != '4.7.3':
                raise ValueError('APK identity does not match the requested version')
            methods = []
            flags = {'tgHost': False, 'jjtvHost': False, 'tgEmbedPath': False, 'jjtvPindPath': False}
            for name in dex_names:
                dex = DEX(archive.read(name))
                methods.extend(routing_methods(dex))
                for value in dex.get_strings():
                    flags['tgHost'] |= 'livetgtv.lovable.app' in value
                    flags['jjtvHost'] |= 'jjtvxweb.pages.dev' in value
                    flags['tgEmbedPath'] |= '/embed/' in value
                    flags['jjtvPindPath'] |= '/pind?id=' in value
        abis = sorted({n.split('/')[1] for n in names if n.startswith('lib/') and n.endswith('.so')
                       and n.split('/')[1] in {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}})
    return {'package': package, 'version': version, 'versionCode': code, 'abis': abis,
            'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'stringPresenceOnly': flags,
            'inspectionCompleted': True, 'methodCount': len(methods), 'methods': methods,
            'apkExecuted': False, 'devicePlaybackTested': False}


def main():
    directory = Path(os.environ.get('RUNNER_TEMP', tempfile.gettempdir())) / 'legacy-apk-inspection'
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / 'legacy.apk'
    report_path = directory / 'redacted-report.json'
    try:
        size = download(path)
        report = inspect(path)
        report['apkBytes'] = size
        report_path.write_text(json.dumps(report, indent=2))
        summary = {k: v for k, v in report.items() if k != 'methods'}
        summary['routingCalls'] = [{k: m[k] for k in ('class', 'method', 'calls')} for m in report['methods']
                                  if m['method'] in {'play', 'wantsTg', 'startEmbed', 'fallbackToTg', 'onStreamError', 'resolveAndStart', 'factory', 'jarFor'}]
        text = json.dumps(summary)
        for index in range(0, min(len(text), 6000), 3000):
            chunk = text[index:index + 3000].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
            print(f'::notice title=Legacy APK inspection {index // 3000 + 1}::{chunk}')
        # Small routing body only. General decompilation/constant dumps must not be published.
        for name in ('play', 'wantsTg', 'startEmbed'):
            for method in report['methods']:
                if method['class'] == 'MainActivity' and method['method'] == name:
                    body = '\n'.join(method['instructions'])[:6000]
                    for index in range(0, len(body), 3000):
                        chunk = body[index:index+3000].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
                        print(f'::notice title=Legacy routing {name} {index//3000+1}::{chunk}')
    except Exception as error:
        # Exception messages/download URLs/parser output can contain private confirmation strings.
        report_path.write_text(json.dumps({'inspectionCompleted': False, 'errorClass': type(error).__name__, 'apkExecuted': False}))
        print(f'::warning title=Legacy APK inspection incomplete::{type(error).__name__}; no binary/playback conclusion. Upload the old APK if its public link is unavailable.')
        raise SystemExit(1)
    finally:
        path.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
