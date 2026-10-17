const test = require('node:test');
const assert = require('node:assert/strict');
const { safeUrl, redactCode, sourceShape, publicHttps } = require('./inspect-tgfrost-player.cjs');

const secret = 'synthetic-secret-not-a-real-token';

test('external media URLs expose only origin, extension and known parameter names', () => {
  const safe = safeUrl(`https://cdn.example.test/private/${secret}.mp4?token=${secret}&opaqueParameterName=${secret}#${secret}`);
  assert.equal(safe, 'https://cdn.example.test/[path redacted].mp4?token=[redacted]&[other]=[redacted]');
  assert.ok(!safe.includes(secret));
  assert.ok(!safe.includes('opaqueParameterName'));
});

test('public Frost title routes and asset names remain identifiable, but arbitrary paths do not', () => {
  assert.equal(safeUrl('https://tgfrost.lovable.app/watch/tv/213562/1/1'), 'https://tgfrost.lovable.app/watch/tv/213562/1/1');
  assert.equal(safeUrl('https://tgfrost.lovable.app/assets/Watch-abc123.js'), 'https://tgfrost.lovable.app/assets/Watch-abc123.js');
  assert.equal(safeUrl(`https://tgfrost.lovable.app/api/stream/${secret}.mp4?key=${secret}`), 'https://tgfrost.lovable.app/api/stream/[path redacted]?key=[redacted]');
});

test('URL userinfo, fragments and opaque/non-HTTP URL bodies are never emitted', () => {
  assert.equal(safeUrl(`https://user:${secret}@cdn.example.test/stream.m3u8#${secret}`), 'https://cdn.example.test/[path redacted].m3u8');
  assert.equal(safeUrl(`blob:https://tgfrost.lovable.app/${secret}`), 'blob:');
  assert.equal(safeUrl(`data:application/octet-stream,${secret}`), 'data:');
  assert.equal(safeUrl('not a URL'), '[invalid URL]');
  assert.equal(safeUrl('null'), 'null');
});

test('code redaction covers URLs, common credentials, hex, JWT and opaque string literals', () => {
  const inputs = [
    'apiToken="short-secret"', 'Authorization:"Bearer short-secret"',
    'api_key="short-secret"', 'cookie:"short-secret"',
    '"aabbccddeeff00112233445566778899"', '"ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"',
    'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature',
    `"https://cdn.example.test/${secret}.mp4?token=${secret}"`
  ];
  for (const input of inputs) {
    const safe = redactCode(input);
    assert.ok(!safe.includes('short-secret'));
    assert.ok(!safe.includes('aabbccddeeff00112233445566778899'));
    assert.ok(!safe.includes('ABCDEFGHIJKLMNOPQRSTUVWXYZ012345'));
    assert.ok(!safe.includes('eyJhbGci'));
    assert.ok(!safe.includes(secret));
  }
});

test('source summaries retain formats and header names, never supplied values', () => {
  const shape = sourceShape({ data: { sources: [{
    file: `https://media.example.test/${secret}.mp4?token=${secret}`, type: 'mp4',
    headers: { Referer: secret, Origin: secret, Authorization: secret, Cookie: secret, 'X-Private': secret },
    quality: '1080p', subtitles: [{ url: `https://subs.example.test/${secret}.vtt` }], token: secret
  }] } });
  assert.deepEqual(shape.formats, ['mp4']);
  assert.deepEqual(new Set(shape.headerNames), new Set(['referer', 'origin', 'authorization', 'cookie']));
  assert.ok(shape.fields.includes('sources'));
  assert.ok(shape.fields.includes('subtitles'));
  assert.equal(shape.mediaUrls.length, 2);
  assert.equal(shape.drmConfigurationPresent, false);
  assert.ok(!JSON.stringify(shape).includes(secret));
});

test('DRM configuration is reported as presence only, without keys or licence contents', () => {
  for (const config of [{ drm: { key: secret } }, { clearKeys: { [secret]: secret } }, { license_url: secret }]) {
    const shape = sourceShape(config);
    assert.equal(shape.drmConfigurationPresent, true);
    assert.ok(!JSON.stringify(shape).includes(secret));
  }
});

test('source traversal is bounded and does not echo arbitrary provider field names or text', () => {
  const shape = sourceShape({ [secret]: secret, sources: Array.from({ length: 500 }, (_, index) => ({
    url: `https://media.example.test/${index}.mp4?token=${secret}`, error: secret
  })) });
  assert.ok(shape.objects <= 12);
  assert.ok(shape.mediaUrls.length <= 12);
  assert.ok(!JSON.stringify(shape).includes(secret));
});

test('request guard rejects plaintext, userinfo and local/IP destinations', () => {
  for (const url of ['http://example.test/video.mp4', 'file:///etc/passwd', 'https://localhost/test',
    'https://app.localhost/test', 'https://127.0.0.1/test', 'https://10.0.0.1/test',
    'https://[::1]/test', 'https://metadata.google.internal/test', 'https://device.local/test',
    'https://device.lan/test', 'https://user:password@example.test/test', 'https://2130706433/test']) {
    assert.equal(publicHttps(url), false, url);
  }
  assert.equal(publicHttps('https://tgfrost.lovable.app/watch/movie/1288069'), true);
  assert.equal(publicHttps('https://cdn.example.test/video.mp4'), true);
});
