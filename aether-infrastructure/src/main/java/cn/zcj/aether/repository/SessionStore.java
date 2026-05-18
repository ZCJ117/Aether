package cn.zcj.aether.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话持久化存储
 * Phase 1: 内存实现 (后续可升级为 Redis/MySQL)
 */
@Slf4j
@Service
public class SessionStore {

    private final Map<String, SessionRecord> sessions = new ConcurrentHashMap<>();

    public String createSession(String appName, String userId) {
        String sessionId = java.util.UUID.randomUUID().toString();
        sessions.put(sessionId, new SessionRecord(sessionId, appName, userId, System.currentTimeMillis()));
        log.info("Session created: {} appName={} userId={}", sessionId, appName, userId);
        return sessionId;
    }

    public SessionRecord get(String sessionId) {
        return sessions.get(sessionId);
    }

    public record SessionRecord(String sessionId, String appName, String userId, long createdAt) {}
}
