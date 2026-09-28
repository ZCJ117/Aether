package cn.zcj.aether.rag;

import cn.zcj.aether.domain.agent.service.retrieval.rag.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D4/F3-3: dev profile 的 RAG 启用是被<b>配置文件</b>落实的 —— 锁住 application-dev.yml。
 *
 * <p><b>为什么需要本用例</b>：{@code RagEnabledRecallIT} 标了 {@code @Tag("integration")}，
 * 需要 pgvector + dev 基础设施才能跑（默认测试集不执行），且它刻意把
 * {@code rewrite.enabled} 覆盖为 false 以避开外部 LLM 依赖 —— 因此它<b>并不校验 dev 形态本身</b>。
 * 本用例直接读 dev yml 并经真实 {@link Binder} 绑到 {@link RagProperties}，
 * 既锁内容也锁键名（防止把 {@code rewrite} 写错成别的键 —— 那样"打开了"只是错觉）。</p>
 *
 * <p>不需要任何外部依赖，随默认测试集执行。</p>
 */
class DevProfileRagConfigTest {

    /**
     * 按 "application.yml → application-dev.yml" 的真实叠加顺序绑定 {@code aether.rag}。
     *
     * <p>用 {@code addFirst} 逐份插入：dev 后插，故排在 application.yml 之前 —— 与 Spring Boot
     * "profile 覆盖基准配置"的优先级一致，也不受运行环境已有属性干扰。</p>
     */
    private static RagProperties bindDevProfileRag() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String file : List.of("application.yml", "application-dev.yml")) {
            for (PropertySource<?> source : loader.load(file, new ClassPathResource(file))) {
                env.getPropertySources().addFirst(source);
            }
        }
        return Binder.get(env).bind("aether.rag", RagProperties.class).orElseThrow(
                () -> new AssertionError("application-dev.yml 中必须存在 aether.rag 配置块"));
    }

    @Test
    @DisplayName("F3-3 dev profile 打开 RAG 总开关与一级改写")
    void devProfileEnablesRagAndRewrite() throws Exception {
        RagProperties props = bindDevProfileRag();

        assertTrue(props.isEnabled(),
                "application-dev.yml 必须把 aether.rag.enabled 置为 true，否则三级管道永不接管");
        assertTrue(props.getRewrite().isEnabled(),
                "application-dev.yml 必须把 aether.rag.rewrite.enabled 置为 true（一级 LLM 改写）");
    }

    @Test
    @DisplayName("F3-3 dev 不改动其它级：hybrid 保持开、rerank 保持关")
    void devProfileLeavesOtherStagesAtBaseValues() throws Exception {
        RagProperties props = bindDevProfileRag();

        assertTrue(props.getHybrid().isEnabled(),
                "hybrid 由 application.yml 置 true，dev 不应改动");
        assertFalse(props.getRerank().isEnabled(),
                "rerank 保持 false —— 这正是启动自检报 rerank=off(关闭) 而非 off(端口缺失) 的事实依据"
                        + "（F3-1 用 matchIfMissing=true 后端口恒装配，关闭的是开关而非端口）");
    }
}
