import { Trend } from 'k6/metrics';
import { check } from 'k6';
import { chatSync, obtainToken } from './lib.js';

/**
 * P0(1.5) LLM 响应缓存命中验证（仅缓冲路径 true-streaming=false 相位）。
 *
 * ModelCallCache：key = modelName + messages hash，TTL 60s；
 * 同一消息重复请求 → 首次 ~4.5s（mock 总时长），其后毫秒级返回。
 * 命中率指标：aether.cache.llm.hitrate（Gauge，本次改动接入 Micrometer）。
 */
const REPEATS = Number(__ENV.REPEATS || 20);
const coldDuration = new Trend('cache_cold_duration_ms', true);
const warmDuration = new Trend('cache_warm_duration_ms', true);

export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate>0.99'] } };

export function setup() {
  return { token: obtainToken(__ENV.BENCH_USER || 'bench-user', __ENV.BENCH_PASSWORD || 'bench-pass-123') };
}

export default function (data) {
  const agentId = __ENV.AGENT_ID || '201';
  const message = '缓存命中验证：固定消息（同一 key）不应重复调用模型。';
  const xff = '10.79.0.1';

  const cold = chatSync(data.token, agentId, 'cache-vu1', null, message, xff, { name: 'cache_cold' });
  coldDuration.add(cold.durationMs);
  check(cold, { 'cold call ok': (x) => x.ok });

  for (let i = 1; i <= REPEATS; i++) {
    const warm = chatSync(data.token, agentId, 'cache-vu1', null, message, xff, { name: 'cache_warm' });
    warmDuration.add(warm.durationMs);
    check(warm, { [`warm call ${i} ok`]: (x) => x.ok });
  }
}

export function handleSummary(data) {
  const p95warm = data.metrics.cache_warm_duration_ms.values['p(95)'];
  const lines = [
    `cache: cold=${Math.round(data.metrics.cache_cold_duration_ms.values.med)}ms,`,
    `warm p95=${Math.round(p95warm)}ms (期望远低于冷调用；命中率见 aether.cache.llm.hitrate)`,
  ];
  const out = { stdout: lines.join(' ') + '\n' };
  if (__ENV.OUT) out[__ENV.OUT] = JSON.stringify(data, null, 2);
  return out;
}
