package cn.zcj.aether.eval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P0(4.1) 确定性工具路由器 —— 确定性 eval 模式的"mock LLM 决策核"。
 *
 * <p>以 用户消息 与 工具描述 的词元重叠度选工具：
 * CJK 连续段切二元组（bigram），拉丁/数字串按词切分。
 * 选择逻辑依赖工具描述文案 —— 这正是 A/B 回归演示的注入点：
 * 篡改一条工具描述（如把"天气"改成"股票"）会真实地改变路由结果并被 eval 捕获。</p>
 */
public class ToolRouter {

    /** 返回得分最高的工具名；无工具或全零分返回 null。 */
    public static String select(String userMessage, List<EvalCase.ToolDef> tools) {
        Set<String> msgTokens = tokenize(userMessage);
        String best = null;
        int bestScore = 0;
        for (EvalCase.ToolDef tool : tools) {
            int score = overlap(msgTokens, tokenize(tool.description));
            if (score > bestScore) {
                bestScore = score;
                best = tool.name;
            }
        }
        return best;
    }

    static int overlap(Set<String> a, Set<String> b) {
        int n = 0;
        for (String t : a) {
            if (b.contains(t)) n++;
        }
        return n;
    }

    /** CJK 段切 bigram；拉丁/数字按非字母数字边界切词并小写化。 */
    static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        StringBuilder latin = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        Runnable flushLatin = () -> {
            if (latin.length() > 0) {
                String w = latin.toString().toLowerCase();
                if (w.length() > 1) tokens.add(w);
                latin.setLength(0);
            }
        };
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                flushLatin.run();
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                if (cjk.length() > 0) {
                    addBigrams(tokens, cjk.toString());
                    cjk.setLength(0);
                }
                latin.append(c);
            } else {
                flushLatin.run();
                if (cjk.length() > 0) {
                    addBigrams(tokens, cjk.toString());
                    cjk.setLength(0);
                }
            }
        }
        flushLatin.run();
        if (cjk.length() > 0) {
            addBigrams(tokens, cjk.toString());
        }
        return tokens;
    }

    private static void addBigrams(Set<String> tokens, String s) {
        for (int i = 0; i + 1 < s.length(); i++) {
            tokens.add(s.substring(i, i + 2));
        }
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    /** 供断言失败信息使用：各工具得分。 */
    public static Map<String, Integer> scores(String userMessage, List<EvalCase.ToolDef> tools) {
        Set<String> msgTokens = tokenize(userMessage);
        Map<String, Integer> out = new HashMap<>();
        for (EvalCase.ToolDef tool : tools) {
            out.put(tool.name, overlap(msgTokens, tokenize(tool.description)));
        }
        return out;
    }
}
