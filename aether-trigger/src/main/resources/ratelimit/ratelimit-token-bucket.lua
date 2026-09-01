-- P1(1.2): Redis 令牌桶 —— 原子执行「计算补充 → 扣减 → 写回 + TTL」
-- KEYS[1] = 桶 key（aether:ratelimit:{scope}:{key}）
-- ARGV[1] = capacity（桶容量）
-- ARGV[2] = refillPerMinute（稳态补充速率）
-- ARGV[3] = nowMicros（调用方时钟，微秒）
-- ARGV[4] = cost（本次扣减令牌数，固定 1）
-- 返回 {allowed(0/1), remaining(剩余令牌向下取整), retryAfterSeconds(拒绝时>0)}

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_per_min = tonumber(ARGV[2])
local now_us = tonumber(ARGV[3])
local cost = tonumber(ARGV[4])

local refill_interval_us = 60000000 / refill_per_min

local bucket = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local ts = tonumber(bucket[2])

-- 首次访问：满桶冷启动
if tokens == nil then
    tokens = capacity
    ts = now_us
end

-- 连续补充（按流逝时间线性补足，非整窗重置——消除固定窗口临界突刺）
if now_us > ts then
    local refill = math.floor((now_us - ts) / refill_interval_us)
    if refill > 0 then
        tokens = math.min(capacity, tokens + refill)
        ts = ts + refill * refill_interval_us
    end
end

local allowed = 0
local retry_after_ms = 0
if tokens >= cost then
    tokens = tokens - cost
    allowed = 1
else
    retry_after_ms = math.ceil((cost - tokens) * refill_interval_us / 1000)
end

redis.call('HSET', key, 'tokens', tokens, 'ts', ts)
-- TTL = 补满一桶所需时间 + 60s 缓冲，空闲桶自动回收
redis.call('PEXPIRE', key, math.ceil(capacity / refill_per_min * 60000) + 60000)

local remaining = math.floor(tokens)
return { allowed, remaining, math.ceil(retry_after_ms / 1000) }
