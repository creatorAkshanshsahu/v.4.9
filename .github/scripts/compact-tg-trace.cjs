// Input is already redacted by trace-tg-browser.cjs. Preserve valid JSON under Checks' notice cap.
function compactTrace(result, limit = 6000) {
  const agents = [...new Set(result.records.filter(r => r.userAgent).map(r => r.userAgent))].slice(0, 3);
  const seen = new Map();
  const records = [];
  for (const record of result.records) {
    if (['state', 'media-request'].includes(record.event)) continue;
    const value = { ...record };
    if (value.userAgent) { value.agent = agents.indexOf(value.userAgent); delete value.userAgent; }
    const key = JSON.stringify([value.event, value.url, value.status, value.origin, value.referer]);
    if (value.event === 'media-response' && seen.has(key)) {
      const first = seen.get(key); first.repeats = (first.repeats || 1) + 1; first.lastElapsedMs = value.elapsedMs;
    } else { seen.set(key, value); records.push(value); }
  }
  const metadata = result.metadata.filter((value, index, all) => index === all.findIndex(other =>
    JSON.stringify([value.stage, value.url, value.status, value.origin, value.referer]) ===
    JSON.stringify([other.stage, other.url, other.status, other.origin, other.referer])));
  const report = { ...result, metadata, userAgents: agents, records, omittedRecords: 0 };
  let text = JSON.stringify(report);
  while (text.length > limit && report.records.length > 2) {
    report.records.splice(1, 1); report.omittedRecords++; text = JSON.stringify(report);
  }
  if (text.length > limit) {
    report.lastFrames = result.lastFrames.map(f => ({ url: f.url, videos: f.videos }));
    report.detailsBounded = true; text = JSON.stringify(report);
  }
  if (text.length > limit) {
    // No partial JSON and no false playback inference, even if upstream metadata grew unexpectedly.
    return JSON.stringify({ channel: result.channel, entrypoint: result.entrypoint,
      videoObserved: result.videoObserved, visitedJjtv: result.visitedJjtv,
      detailsBounded: true, metadata: metadata.map(m => ({ stage: m.stage, status: m.status, origin: m.origin, referer: m.referer })) });
  }
  return text;
}
module.exports = { compactTrace };
