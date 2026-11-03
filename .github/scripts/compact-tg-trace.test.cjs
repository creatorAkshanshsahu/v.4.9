const { test } = require('node:test');
const assert = require('node:assert/strict');
const { compactTrace } = require('./compact-tg-trace.cjs');
const base = () => ({ channel: '872', entrypoint: 'TG-embedded', videoObserved: false, visitedJjtv: true,
  metadata: [{ stage: 'player-page', status: 200, url: 'https://jjtvxweb.pages.dev/pind?id=[redacted]', origin: '', referer: '' }],
  records: [], lastFrames: [], blockedHosts: {}, blockedNonPlayerRequests: 0 });
test('trace JSON stays whole and fits at most two notices', () => {
  const data = base();
  data.records = Array.from({ length: 90 }, (_, n) => ({ event: 'media-response', url: 'https://media.example/' + n,
    status: 451, userAgent: 'public-agent'.repeat(12), origin: 'https://jjtvxweb.pages.dev', referer: '', elapsedMs: n }));
  const encoded = compactTrace(data);
  assert.ok(encoded.length <= 6000);
  const report = JSON.parse(encoded);
  assert.equal(report.videoObserved, false);
  assert.equal(report.metadata[0].stage, 'player-page');
  assert.ok(report.omittedRecords > 0);
});
test('repeated failures preserve count instead of repeating the UA', () => {
  const data = base();
  data.records = Array.from({ length: 4 }, (_, n) => ({ event: 'media-response', url: 'https://media.example/a.mpd',
    status: 403, userAgent: 'public-agent', origin: 'null', referer: '', elapsedMs: n }));
  const report = JSON.parse(compactTrace(data));
  assert.equal(report.records.length, 1);
  assert.equal(report.records[0].repeats, 4);
  assert.deepEqual(report.userAgents, ['public-agent']);
});
test('parent and iframe catalogue request contexts are kept distinct', () => {
  const data = base();
  data.metadata = [{ stage: 'channel-catalogue', url: 'https://jjtvxweb.pages.dev/jstr4web.json', status: 200, origin: 'https://livetgtv.lovable.app', referer: 'https://livetgtv.lovable.app' },
    { stage: 'channel-catalogue', url: 'https://jjtvxweb.pages.dev/jstr4web.json', status: 200, origin: '', referer: '' }];
  assert.equal(JSON.parse(compactTrace(data)).metadata.length, 2);
});
