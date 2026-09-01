package cn.zcj.aether.infrastructure.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;

/**
 * P1(1.3): 消费幂等仓储（aether_processed_event）。
 *
 * <p>{@code INSERT ... ON CONFLICT DO NOTHING} 原子判定：返回 true = 首次处理，
 * 返回 false = 重复投递（至少一次语义下安全去重）。清理策略：由运维按
 * {@code processed_at} 定期归档（事件量 = turn/工具粒度，按周分区即可）。</p>
 */
@Slf4j
@Repository
public class ProcessedEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProcessedEventRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /** @return true=首次出现（应继续处理）；false=重复消息（跳过） */
    public boolean markProcessed(String eventId, String eventType) {
        int rows = jdbcTemplate.update(
            "INSERT INTO aether_processed_event (event_id, event_type) VALUES (?, ?) " +
            "ON CONFLICT (event_id) DO NOTHING", eventId, eventType);
        return rows > 0;
    }
}
