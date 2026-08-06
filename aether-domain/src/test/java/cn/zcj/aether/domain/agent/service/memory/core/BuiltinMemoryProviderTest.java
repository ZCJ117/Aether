package cn.zcj.aether.domain.agent.service.memory.core;

import cn.zcj.aether.domain.agent.service.memory.MemoryRecord;
import cn.zcj.aether.domain.agent.service.memory.MemoryScope;
import cn.zcj.aether.domain.agent.service.memory.MemorySearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BuiltinMemoryProviderTest {

    private static final List<String> TOPICS = List.of(
            "数据库连接池", "定时任务", "消息队列", "缓存策略", "权限校验",
            "日志链路", "灰度发布", "配置中心", "分布式锁", "限流降级");

    private FakeMemoryFacade facade;
    private MemoryProperties props;
    private BuiltinMemoryProvider provider;

    @BeforeEach
    void setUp() {
        facade = new FakeMemoryFacade();
        props = new MemoryProperties();
        provider = new BuiltinMemoryProvider(facade, props);
    }

    @Test
    void nameAndAvailability() {
        assertEquals("builtin", provider.name());
        assertTrue(provider.isAvailable());
        assertEquals(List.of(), provider.getToolSchemas());
    }

    @Test
    void syncTurnPersistsContentThroughFacade() {
        provider.syncTurn("用户问题", "助手回答关于数据库连接池的内容", "s1", null);
        assertEquals(1, facade.records.size());
        assertTrue(facade.records.values().iterator().next().getContent().contains("数据库连接池"));
    }

    @Test
    void prefetchWrapsResultsInFencedBlock() {
        provider.syncTurn("用户问", "助手回答关于分布式锁的内容", "s1", null);
        provider.syncTurn("用户问", "助手回答关于缓存策略的内容", "s1", null);
        String block = provider.prefetch("分布式锁 怎么用", "s1");
        assertTrue(block.startsWith("<memory-context>"));
        assertTrue(block.endsWith("</memory-context>"));
        assertTrue(block.contains("[System note:"));
        assertTrue(block.contains("分布式锁"));
    }

    @Test
    void classifyScopeUsesUserKeywords() {
        MemoryScope agent = provider.classifyScope("环境变量 AETHER_HOME 指向配置文件");
        MemoryScope user = provider.classifyScope("我喜欢简洁的回答风格");
        assertEquals("agent", agent.path());
        assertTrue(user.path().startsWith("user"));
    }

    @Test
    void prefetchTruncatesByCharLimit() {
        // header 固定 ~206 字符；预算容纳第一条、裁掉第二条，验证"部分保留"分支
        props.setMemoryCharLimit(240);
        provider.syncTurn("用户问", "助手回答关于分布式锁的内容", "s1", null);
        provider.syncTurn("用户问", "助手回答关于缓存策略的内容", "s1", null);
        String block = provider.prefetch("分布式锁", "s1");
        assertTrue(block.endsWith("</memory-context>"));
        assertTrue(block.contains("System note"));
        // 第一条保留、第二条被裁 → 截断标记出现在闭合围栏之前
        assertTrue(block.contains("分布式锁"));
        assertTrue(block.contains("记忆已截断"));
        assertTrue(block.indexOf("记忆已截断") < block.indexOf("</memory-context>"));
    }

    @Test
    void truncatedBlockStillRoundTripsThroughSanitize() {
        props.setMemoryCharLimit(240);
        provider.syncTurn("用户问", "助手回答关于分布式锁的内容", "s1", null);
        provider.syncTurn("用户问", "助手回答关于缓存策略的内容", "s1", null);
        String block = provider.prefetch("分布式锁", "s1");
        // 截断后的块围栏结构完整，仍可被净化器完整剥除
        assertEquals("", MemoryContextScrubber.sanitize(block).trim());
    }

    @Test
    void assistantMentioningUserProfileKeywordStaysInAgentScope() {
        // 用户文本无画像关键词，仅助手回复含 "我是" → 仍归 agent 作用域（防误分类）
        provider.syncTurn("帮我看看这个配置", "我是这样实现的：设置超时时间", "s1", null);
        MemoryRecord r = facade.records.values().iterator().next();
        assertEquals("agent", r.getScope().path());
    }

    @Test
    void prefetchReturnsEmptyWhenFacadeNull() {
        BuiltinMemoryProvider bare = new BuiltinMemoryProvider(null, props);
        assertEquals("", bare.prefetch("q", "s"));
    }

    @Test
    void formatMemoryBlockEmptyForNoResults() {
        assertEquals("", BuiltinMemoryProvider.formatMemoryBlock(List.of(), 100));
        assertEquals("", BuiltinMemoryProvider.formatMemoryBlock(null, 100));
    }

    /** 核心验收：写入 10 条不同主题记忆 → 精确检索 → 目标主题被召回且排位第一。 */
    @Test
    void writeTenThenPreciseRecall() {
        for (int i = 0; i < TOPICS.size(); i++) {
            provider.syncTurn("用户提问", "助手回答关于" + TOPICS.get(i) + "的配置说明", "s1", null);
        }
        assertEquals(10, facade.records.size());

        String queryTopic = TOPICS.get(4); // 权限校验
        String block = provider.prefetch(queryTopic + " 怎么配置", "s1");

        String target = queryTopic + "的配置说明";
        assertTrue(block.contains(target), "应召回目标记忆，实际: " + block);

        int pos1 = block.indexOf("记忆1: ");
        int pos2 = block.indexOf("记忆2: ");
        assertTrue(pos1 >= 0);
        String firstBlock = pos2 < 0 ? block.substring(pos1) : block.substring(pos1, pos2);
        assertTrue(firstBlock.contains(queryTopic),
                "目标主题应排位第一，实际第一条: " + firstBlock);
    }
}
