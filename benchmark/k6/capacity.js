import { Counter, Rate, Trend } from 'k6/metrics';
import { check, sleep } from 'k6';
import { chatStream, createSession, obtainToken } from './lib.js';

/**
 * P0(1.5) 并发容量压测：N 并发会话 × TURNS 轮流式对话。
 *
 * 运行（run.sh 调用，亦可单独）：
 *   k6 run -e VUS=30 -e TURNS=10 -e AGENT_ID=201 -e OUT=results/capacity_30.json capacity.js
 *
 * 语义：每个 VU = 1 条长会话（create_session 后逐轮流式对话）；
 *       消息按 VU+轮次唯一，避免缓冲相位命中 LLM 响应缓存。
 */
const VUS = Number(__ENV.VUS || 10);
const TURNS = Number(__ENV.TURNS || 10);

const turnDuration = new Trend('chat_turn_duration_ms', true);
const sessionDuration = new Trend('chat_session_duration_ms', true);
const turnErrors = new Rate('chat_turn_errors');
const doneFrames = new Counter('chat_done_frames');

export const options = {
  scenarios: {
    capacity: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: `${Math.max(30, (TURNS * 6 * VUS) / Math.max(VUS, 1))}m`,
      gracefulStop: '60s',
    },
  },
  thresholds: {
    chat_turn_errors: ['rate<0.05'],
  },
};

export function setup() {
  return { token: obtainToken(__ENV.BENCH_USER || 'bench-user', __ENV.BENCH_PASSWORD || 'bench-pass-123') };
}

export default function (data) {
  const agentId = __ENV.AGENT_ID || '201';
  const vu = (__VU % 100000) + 1;
  const userId = `bench-vu${vu}`;
  const xff = `10.77.${vu % 250}.${(vu % 200) + 10}`;

  const sessionId = createSession(data.token, agentId, userId, xff);
  const sessionStart = Date.now();

  for (let turn = 1; turn <= TURNS; turn++) {
    const message = `压测第${turn}轮（vu=${vu}，时间戳=${Date.now()}）：请基于上下文继续回答。`;
    const r = chatStream(data.token, agentId, userId, sessionId, message, xff, {
      name: 'chat_stream',
    });
    turnDuration.add(r.durationMs);
    const ok = r.status === 200 && r.doneSeen && !r.errorSeen;
    turnErrors.add(!ok);
    if (r.doneSeen) doneFrames.add(1);
    check(r, {
      'turn completed': (x) => x.status === 200 && x.doneSeen,
      'turn has content': (x) => x.textChars > 0,
    });
    sleep(0.2);
  }
  sessionDuration.add(Date.now() - sessionStart);
}

export function handleSummary(data) {
  const lines = [
    `VUS=${VUS} TURNS=${TURNS} AGENT=${__ENV.AGENT_ID || 201}`,
    `turns p50=${percentile(data, 'chat_turn_duration_ms', 0.5)}ms p95=${percentile(data, 'chat_turn_duration_ms', 0.95)}ms`,
    `turn error rate=${data.metrics.chat_turn_errors ? data.metrics.chat_turn_errors.value : 'n/a'}`,
  ];
  const out = { stdout: lines.join('\n') + '\n' };
  if (__ENV.OUT) out[__ENV.OUT] = JSON.stringify(data, null, 2);
  return out;
}

function percentile(data, name, q) {
  const m = data.metrics[name];
  if (!m) return 'n/a';
  const key = `p(${Math.round(q * 100)})`;
  return m.values && m.values[key] !== undefined ? Math.round(m.values[key]) : 'n/a';
}
