package cn.zcj.aether.domain.agent.service.agent.permission;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Prompt 注入防护规则 —— 前置拦截已知注入攻击模式。
 * 灵感来源：AgentScope deny-first 安全链 + AutoGen 消息拦截语义。
 *
 * <p>检测工具入参中的文本载荷与用户原始输入，匹配注入特征模式。
 * 命中即返回 ASK_USER，走人工确认通道。
 *
 * <p>规则集可通过 YAML 配置扩展（默认提供中英文常见注入模式）。
 */
@Slf4j
public class InjectionGuardRule implements PermissionRule {

    /**
     * 默认注入检测模式（中英文）。
     * 可通过构造函数或 setPatterns() 扩展。
     */
    private static final List<Pattern> DEFAULT_PATTERNS = List.of(
            // 英文注入模式
            Pattern.compile("ignore\\s+(all\\s+)?(previous|prior|above|the\\s+above)\\s+instructions?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard\\s+(all\\s+)?(previous|prior|above)\\s+instructions?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("forget\\s+(all\\s+)?(previous|prior|earlier)\\s+instructions?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("you\\s+are\\s+now\\s+(a\\s+)?(DAN|jailbreak|evil|malicious)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("system\\s*prompt\\s*(leak|reveal|expose|show|display|print|output)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(print|show|reveal|output|display)\\s+(your|the)\\s+(system\\s*)?(prompt|instructions|rules)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("act\\s+as\\s+(if\\s+you\\s+are\\s+)?(a\\s+)?(different|another)\\s+(AI|model|system)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("pretend\\s+(you\\s+are|to\\s+be)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("from\\s+now\\s+on\\s+you\\s+(are|will\\s+be)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("new\\s+(system\\s*)?(prompt|instructions?|rules?)\\s*:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("```\\s*system\\s*\\n", Pattern.CASE_INSENSITIVE),

            // 中文注入模式
            Pattern.compile("忽略(所有)?(之前的|上述的|前面的)?指令"),
            Pattern.compile("忘记(所有)?(之前的|前面的)?(指令|规则|提示)"),
            Pattern.compile("你现在是"),
            Pattern.compile("(泄露|暴露|显示|输出|打印)(你的|系统)?(提示词|系统提示|指令|规则)"),
            Pattern.compile("假装你是"),
            Pattern.compile("从现在开始你是")
    );

    private List<Pattern> patterns;

    public InjectionGuardRule() {
        this.patterns = DEFAULT_PATTERNS;
    }

    public InjectionGuardRule(List<Pattern> customPatterns) {
        this.patterns = customPatterns != null && !customPatterns.isEmpty()
                ? List.copyOf(customPatterns)
                : DEFAULT_PATTERNS;
    }

    @Override
    public String name() {
        return "injection-guard";
    }

    @Override
    public int priority() {
        return 8; // 在 deny 组内，晚于脱敏规则(p=5)但早于工具白名单(p=15)
    }

    @Override
    public PermissionDecision evaluate(PermissionContext ctx) {
        Map<String, Object> toolInput = ctx.getToolInput();
        if (toolInput == null || toolInput.isEmpty()) {
            return null; // 无输入参数，无需检测
        }

        // 遍历所有字符串类型参数值做注入检测
        for (Object value : toolInput.values()) {
            if (value instanceof String text && !text.isEmpty()) {
                if (detectInjection(text)) {
                    log.warn("检测到疑似 Prompt 注入攻击: tool={}, userId={}, 命中模式在参数值中",
                            ctx.getToolName(), ctx.getUserId());
                    return PermissionDecision.ASK_USER;
                }
            }
        }

        return null; // 未命中，交给下一条规则
    }

    /**
     * 检测文本是否包含注入模式。
     */
    private boolean detectInjection(String text) {
        String lowerText = text.toLowerCase(Locale.ROOT);
        for (Pattern pattern : patterns) {
            if (pattern.matcher(lowerText).find()) {
                return true;
            }
        }
        return false;
    }

    // ========== 配置方法 ==========

    /** 设置自定义注入检测模式（替换默认规则集） */
    public void setPatterns(List<Pattern> patterns) {
        this.patterns = patterns != null ? List.copyOf(patterns) : DEFAULT_PATTERNS;
    }

    /** 追加额外的注入检测模式 */
    public void addPattern(Pattern pattern) {
        if (pattern != null) {
            List<Pattern> newList = new java.util.ArrayList<>(this.patterns);
            newList.add(pattern);
            this.patterns = List.copyOf(newList);
        }
    }
}
