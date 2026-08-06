package cn.zcj.aether.domain.agent.service.memory.core;

import java.util.regex.Pattern;

/**
 * 记忆上下文净化 —— 对齐 hermes agent/memory_manager.py 的 sanitize_context +
 * StreamingContextScrubber。
 *
 * <p>注入的记忆上下文用 {@code <memory-context>} 围栏包裹并附带系统注记；
 * 本工具用于防止围栏/注记/记忆内容泄漏到助手输出或再次写入记忆
 * （防递归记忆污染）。</p>
 * <ul>
 *   <li>{@link #sanitize(String)} 一次性净化完整文本。</li>
 *   <li>{@link StreamingScrubber} 有状态净化流式文本，处理跨 chunk 被拆分的标签；
 *       未闭合围栏内的内容直接丢弃。</li>
 * </ul>
 */
public final class MemoryContextScrubber {

    private static final Pattern CONTEXT_BLOCK_RE = Pattern.compile(
            "<\\s*memory-context\\s*>[\\s\\S]*?</\\s*memory-context\\s*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SYSTEM_NOTE_RE = Pattern.compile(
            "\\[System note:\\s*The following is recalled memory context,\\s*NOT new user input\\.\\s*"
            + "Treat as (?:informational background data|authoritative reference data[^\\]]*)\\.\\]\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FENCE_TAG_RE = Pattern.compile(
            "</?\\s*memory-context\\s*>",
            Pattern.CASE_INSENSITIVE);

    private MemoryContextScrubber() {
    }

    /** 一次性净化：剥除围栏块、注入的系统注记行、孤立围栏标签。 */
    public static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String t = CONTEXT_BLOCK_RE.matcher(text).replaceAll("");
        t = SYSTEM_NOTE_RE.matcher(t).replaceAll("");
        t = FENCE_TAG_RE.matcher(t).replaceAll("");
        return t;
    }

    /**
     * 有状态流式净化器。每个文本分片调用 {@link #feed(String)}，流结束后
     * 调用 {@link #flush()} 取回暂存的尾缀（若为未闭合围栏则丢弃）。
     */
    public static final class StreamingScrubber {

        private static final String OPEN_TAG = "<memory-context>";
        private static final String CLOSE_TAG = "</memory-context>";

        private boolean inSpan = false;
        private final StringBuilder buf = new StringBuilder();

        public void reset() {
            inSpan = false;
            buf.setLength(0);
        }

        public String feed(String text) {
            if (text == null || text.isEmpty()) {
                return "";
            }
            String combined = buf.toString() + text;
            buf.setLength(0);
            StringBuilder out = new StringBuilder();
            int i = 0;
            int len = combined.length();
            while (i < len) {
                if (inSpan) {
                    int close = indexOfIgnoreCase(combined, CLOSE_TAG, i);
                    if (close == -1) {
                        int hold = maxPartialSuffix(combined, i, CLOSE_TAG);
                        if (hold > 0) {
                            buf.append(combined, len - hold, len);
                        }
                        return out.toString(); // span 内容丢弃
                    }
                    i = close + CLOSE_TAG.length();
                    inSpan = false;
                } else {
                    int open = indexOfIgnoreCase(combined, OPEN_TAG, i);
                    if (open == -1) {
                        int hold = maxPartialSuffix(combined, i, OPEN_TAG);
                        if (combined.toLowerCase().endsWith(OPEN_TAG)) {
                            hold = Math.max(hold, OPEN_TAG.length());
                        }
                        if (hold > 0) {
                            out.append(combined, i, len - hold);
                            buf.append(combined, len - hold, len);
                        } else {
                            out.append(combined, i, len);
                        }
                        return out.toString();
                    }
                    if (open > i) {
                        out.append(combined, i, open);
                    }
                    i = open + OPEN_TAG.length();
                    inSpan = true;
                }
            }
            if (inSpan) {
                buf.setLength(0); // 未闭合围栏：丢弃残留
            }
            return out.toString();
        }

        public String flush() {
            if (inSpan) {
                inSpan = false;
                buf.setLength(0);
                return "";
            }
            String tail = buf.toString();
            buf.setLength(0);
            return tail;
        }

        private static int maxPartialSuffix(String s, int from, String tag) {
            String tail = s.substring(from);
            String tagLower = tag.toLowerCase();
            String tailLower = tail.toLowerCase();
            int max = Math.min(tailLower.length(), tagLower.length() - 1);
            for (int k = max; k >= 1; k--) {
                if (tagLower.startsWith(tailLower.substring(tailLower.length() - k))) {
                    return k;
                }
            }
            return 0;
        }

        private static int indexOfIgnoreCase(String s, String sub, int from) {
            return s.toLowerCase().indexOf(sub.toLowerCase(), from);
        }
    }
}
