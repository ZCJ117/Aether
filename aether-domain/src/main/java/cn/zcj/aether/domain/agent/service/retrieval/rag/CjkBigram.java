package cn.zcj.aether.domain.agent.service.retrieval.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * P1(4.2): CJK 2-gram 文本处理器 —— PG 全文检索的中文适配层（零扩展组件）。
 *
 * <p>PG 自带的 'simple' 分词器按空白切词，对中文无效；安装 zhparser 需要扩展。
 * 本工具在 Java 侧把文本归一化为空格分隔 token 流（CJK 连续段切 2-gram +
 * 拉丁/数字词保留），写入 {@code content_bigram} 列并以
 * {@code to_tsvector('simple', content_bigram)} + GIN 表达式索引供 BM25 风格
 * 词法召回；查询侧对 query 做同样变换。</p>
 *
 * <p>示例：{@code "用户偏好VSCode编辑器"} → {@code "用户 户偏 偏好 vscode 编辑 辑器"}。</p>
 */
public final class CjkBigram {

    private CjkBigram() {
    }

    public static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // CJK 统一表意文字
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0x3040 && c <= 0x30FF); // 平假名/片假名
    }

    /** 文本 → 空格分隔 token 流（bigram + 词）。null/空安全。 */
    public static String bigram(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();

        int i = 0;
        int n = normalized.length();
        while (i < n) {
            char c = normalized.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (isCjk(c)) {
                // 连续 CJK 段 → 2-gram（孤立单字保留 unigram）
                int start = i;
                while (i < n && isCjk(normalized.charAt(i))) {
                    i++;
                }
                appendCjkGrams(normalized, start, i, tokens);
            } else if (Character.isLetterOrDigit(c)) {
                // 连续拉丁/数字段 → 单个词
                int start = i;
                while (i < n && !isCjk(normalized.charAt(i))
                        && !Character.isWhitespace(normalized.charAt(i))
                        && Character.isLetterOrDigit(normalized.charAt(i))) {
                    i++;
                }
                tokens.add(normalized.substring(start, i));
            } else {
                // 标点/符号：作为分隔符跳过
                i++;
            }
        }
        return String.join(" ", tokens);
    }

    private static void appendCjkGrams(String s, int start, int end, List<String> tokens) {
        int len = end - start;
        if (len == 1) {
            tokens.add(s.substring(start, end));
            return;
        }
        for (int i = start; i + 2 <= end; i++) {
            tokens.add(s.substring(i, i + 2));
        }
    }
}
