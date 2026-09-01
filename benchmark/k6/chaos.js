import { Trend } from 'k6/metrics';
import { check } from 'k6';
import { chatSync, obtainToken } from './lib.js';

/**
 * P0(1.5) 容错混沌验证：agent 200 的 api-key 为 bench:429,429,429,500,500,ok。
 *
 * 期望（ResilientChatModelExecutor 行为）：
 *   第 1 次会话经历 429×3（自适应退避）→ 500×2（抖动退避/fallback 切换）→ 成功；
 *   恢复耗时 = 该会话端到端时长（观测值，配合 aether.model.recovery.branch 指标核对路径）；
 *   后续会话全部直接成功（退避后稳态）。
 *
 * 注意：429 退避表为 30/60/90s，单次恢复可能需要数分钟，CHAT_TIMEOUT_MS 需 ≥ 600000。
 */
const recoveryDuration = new Trend('chaos_recovery_duration_ms', true);
const steadyDuration = new Trend('chaos_steady_duration_ms', true);

export const options = {
  vus: 1,
  iterations: Number(__ENV.STEADY_ROUNDS || 3) + 1,
  thresholds: { checks: ['rate>0.99'] },
};

export function setup() {
  return { token: obtainToken(__ENV.BENCH_USER || 'bench-user', __ENV.BENCH_PASSWORD || 'bench-pass-123') };
}

export default function (data) {
  const agentId = __ENV.AGENT_ID || '200';
  const isFirst = __ITER === 0;
  const r = chatSync(
    data.token,
    agentId,
    `chaos-vu${__VU}`,
    null,
    `混沌验证 iter=${__ITER} ts=${Date.now()}`,
    `10.78.0.${__ITER + 1}`,
    { name: 'chaos_chat' }
  );
  if (isFirst) recoveryDuration.add(r.durationMs);
  else steadyDuration.add(r.durationMs);
  check(r, { 'chaos session eventually succeeded': (x) => x.ok && x.content.length > 0 });
}

export function handleSummary(data) {
  const out = { stdout: `chaos done\n` };
  if (__ENV.OUT) out[__ENV.OUT] = JSON.stringify(data, null, 2);
  return out;
}
