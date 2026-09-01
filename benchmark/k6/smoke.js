import { check } from 'k6';
import http from 'k6/http';
import { BASE_URL, obtainToken } from './lib.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate>0.99'] },
};

export function setup() {
  return { token: obtainToken(__ENV.BENCH_USER || 'bench-user', __ENV.BENCH_PASSWORD || 'bench-pass-123') };
}

export default function (data) {
  const health = http.get(`${BASE_URL}/actuator/health`);
  check(health, {
    'health UP': (r) => r.status === 200 && String(r.body).includes('UP'),
  });

  const agents = http.get(`${BASE_URL}/api/v1/query_ai_agent_config_list`, {
    headers: { Authorization: `Bearer ${data.token}` },
  });
  check(agents, {
    'agent list ok': (r) => {
      if (r.status !== 200) return false;
      const b = r.json();
      return b.code === '0000' && Array.isArray(b.data) && b.data.length > 0;
    },
  });

  const chat = http.post(
    `${BASE_URL}/api/v1/chat`,
    JSON.stringify({ agentId: __ENV.AGENT_ID || '201', userId: 'smoke', message: '冒烟测试：请回复一句话。' }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` }, timeout: 600000 }
  );
  check(chat, {
    'chat ok': (r) => {
      if (r.status !== 200) return false;
      const b = r.json();
      return b.code === '0000' && b.data && typeof b.data.content === 'string' && b.data.content.length > 0;
    },
  });
}
