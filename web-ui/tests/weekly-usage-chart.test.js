import assert from "node:assert/strict";
import test from "node:test";
import { weeklyUsageBuckets } from "../src/weekly-usage-chart.js";

test("20-minute and day views keep the last probe in each bucket and exclude old probes", () => {
  const now = Date.UTC(2026, 9, 19, 12, 30);
  const hour = Math.floor(now / 3600000) * 3600;
  const samples = [
    { observed_at: hour + 60, remaining_percent: 70 },
    { observed_at: hour + 19 * 60, remaining_percent: 68 },
    { observed_at: hour + 21 * 60, remaining_percent: null, error_code: "unavailable" },
    { observed_at: hour - 8 * 24 * 3600, remaining_percent: 90 },
  ];
  const hourly = weeklyUsageBuckets(samples, "hour", now);
  const daily = weeklyUsageBuckets(samples, "day", now);
  assert.equal(hourly.length, 504);
  assert.equal(daily.length, 7);
  assert.equal(hourly.at(-1).sample.error_code, "unavailable");
  assert.equal(hourly.at(-2).sample.remaining_percent, 68);
  assert.equal(daily.at(-1).sample.error_code, "unavailable");
  assert.equal(hourly.filter((bucket) => bucket.sample).length, 2);
});
