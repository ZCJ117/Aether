import http from 'k6/http';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8091';
export const CHAT_TIMEOUT_MS = Number(__ENV.CHAT_TIMEOUT_MS || 600000);

/** 登录拿 token；未注册时先注册再登录（重复压测时用户已存在，login 直接成功）。 */
export function obtainToken(username, password) {
  const headers = { 'Content-Type': 'application/json' };
  let login = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ username, password }),
    { headers, tags: { name: 'login' } }
  );
  if (login.status !== 200) {
    // 未注册 → 注册后重试登录（注册失败仅告警：可能并发注册冲突）
    const reg = http.post(
      `${BASE_URL}/api/v1/auth/register`,
      JSON.stringify({ username, password }),
      { headers, tags: { name: 'register' } }
    );
    if (reg.status !== 200) {
      console.warn(`register status=${reg.status} body=${String(reg.body).slice(0, 120)} (可能已注册，继续登录)`);
    }
    login = http.post(
      `${BASE_URL}/api/v1/auth/login`,
      JSON.stringify({ username, password }),
      { headers, tags: { name: 'login' } }
    );
  }
  if (login.status !== 200) throw new Error(`login failed: ${login.status} ${String(login.body).slice(0, 200)}`);
  const body = login.json();
  const token = body && body.data && body.data.accessToken;
  if (!token) throw new Error(`no accessToken in login response: ${String(login.body).slice(0, 200)}`);
  return token;
}

export function authHeaders(token, xff) {
  return {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${token}`,
    // RateLimitFilter 按 X-Forwarded-For 首段分桶：每 VU 独立桶，避免压测触发单 IP 限流
    'X-Forwarded-For': xff,
  };
}

/** 每 VU 独立的伪造转发 IP。 */
export function xffFor(vuId, seq) {
  return `10.77.${vuId % 250}.${(seq % 250) + 1}`;
}

export function chatBody(agentId, userId, sessionId, message) {
  const body = { agentId: String(agentId), userId, message };
  if (sessionId) body.sessionId = sessionId;
  return JSON.stringify(body);
}

/**
 * POST /api/v1/chat_stream（SSE）。返回解析结果：
 * { status, durationMs, frames, textChars, doneSeen, errorSeen }
 * 注：k6 的 HTTP 响应为整读，durationMs = 全流时长；TTFT 由 probe/ttft_probe.py 单独测量。
 */
export function chatStream(token, agentId, userId, sessionId, message, xff, tags) {
  const res = http.post(`${BASE_URL}/api/v1/chat_stream`, chatBody(agentId, userId, sessionId, message), {
    headers: authHeaders(token, xff),
    timeout: CHAT_TIMEOUT_MS,
    tags: tags || { name: 'chat_stream' },
  });
  return parseSse(res);
}

/** POST /api/v1/chat（同步）。返回 { status, ok, content, durationMs }。 */
export function chatSync(token, agentId, userId, sessionId, message, xff, tags) {
  const res = http.post(`${BASE_URL}/api/v1/chat`, chatBody(agentId, userId, sessionId, message), {
    headers: authHeaders(token, xff),
    timeout: CHAT_TIMEOUT_MS,
    tags: tags || { name: 'chat' },
  });
  let ok = false;
  let content = '';
  if (res.status === 200) {
    try {
      const body = res.json();
      ok = body.code === '0000';
      content = body.data && body.data.content ? body.data.content : '';
    } catch (e) {
      ok = false;
    }
  }
  return { status: res.status, ok, content, durationMs: res.timings.duration };
}

export function createSession(token, agentId, userId, xff) {
  const res = http.post(
    `${BASE_URL}/api/v1/create_session`,
    JSON.stringify({ agentId: String(agentId), userId }),
    { headers: authHeaders(token, xff), tags: { name: 'create_session' } }
  );
  if (res.status === 200) {
    const body = res.json();
    if (body.code === '0000' && body.data && body.data.sessionId) return body.data.sessionId;
  }
  throw new Error(`create_session failed: ${res.status} ${String(res.body).slice(0, 200)}`);
}

function parseSse(res) {
  const out = { status: res.status, durationMs: res.timings.duration, frames: 0, textChars: 0, doneSeen: false, errorSeen: false, parseError: false };
  if (res.status !== 200) return out;
  for (const block of String(res.body).split('\n\n')) {
    const line = block.trim();
    if (!line.startsWith('data:')) continue;
    out.frames += 1;
    try {
      const evt = JSON.parse(line.slice(5).trim());
      if (evt.type === 'done') out.doneSeen = true;
      if (evt.type === 'textDelta' && evt.text) out.textChars += evt.text.length;
      if (evt.type === 'error') out.errorSeen = true;
    } catch (e) {
      out.parseError = true;
    }
  }
  return out;
}

/** handleSummary 输出：控制台摘要 + JSON 文件（由 OUT 环境变量指定路径）。 */
export function summaryOutput(data, stdoutText) {
  const out = {};
  if (stdoutText) out.stdout = stdoutText;
  if (__ENV.OUT) out[__ENV.OUT] = JSON.stringify(data, null, 2);
  return out;
}
