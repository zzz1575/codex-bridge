export function weeklyUsageBuckets(samples, scale, now = Date.now()) {
  const count = scale === "day" ? 7 : 7 * 24 * 3;
  const start = new Date(now);
  if (scale === "day") start.setHours(0, 0, 0, 0);
  else start.setMinutes(Math.floor(start.getMinutes() / 20) * 20, 0, 0);
  const buckets = Array.from({ length: count }, (_, index) => {
    const date = new Date(start);
    if (scale === "day") date.setDate(date.getDate() - (count - index - 1));
    else date.setMinutes(date.getMinutes() - (count - index - 1) * 20);
    return { time: date.getTime(), sample: null };
  });
  const indexes = new Map(buckets.map((bucket, index) => [bucket.time, index]));
  const cutoff = now - 7 * 24 * 60 * 60 * 1000;
  for (const sample of samples || []) {
    const time = Number(sample.observed_at) * 1000;
    if (!Number.isFinite(time) || time < cutoff || time > now) continue;
    const local = new Date(time);
    if (scale === "day") local.setHours(0, 0, 0, 0);
    else local.setMinutes(Math.floor(local.getMinutes() / 20) * 20, 0, 0);
    const index = indexes.get(local.getTime());
    if (index === undefined) continue;
    if (!buckets[index].sample || sample.observed_at >= buckets[index].sample.observed_at)
      buckets[index].sample = sample;
  }
  return buckets;
}
