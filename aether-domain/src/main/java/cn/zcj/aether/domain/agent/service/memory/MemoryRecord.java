package cn.zcj.aether.domain.agent.service.memory;

import lombok.Builder;
import lombok.Value;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 记忆记录。
 */
@Value
@Builder
public class MemoryRecord {
    /** 唯一标识 */
    String id;

    /** 记忆文本内容 */
    String content;

    /** Embedding 向量 */
    float[] embedding;

    /** 作用域 */
    MemoryScope scope;

    /** 分类标签 */
    List<String> categories;

    /** 扩展元数据 */
    Map<String, Object> metadata;

    /** 重要性（0.0～1.0） */
    float importance;

    /** 创建时间 */
    Instant createdAt;

    /** 最后访问时间 */
    Instant lastAccessedAt;

    /** 访问次数 */
    int accessCount;

    /** 是否为私密记忆 */
    boolean isPrivate;

    /** 来源：user_manual / agent_extracted / system */
    String source;

    /** P1(4.3): 生命周期状态 —— true=已被遗忘曲线归档（软删；检索默认过滤，再次写入可复活） */
    @Builder.Default
    boolean archived = false;
}
