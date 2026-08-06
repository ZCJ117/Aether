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
        private boolean atBlockBoundary = true;
        private final StringBuilder buf = new StringBuilder();

        public void reset() {
            inSpan = false;
            atBlockBoundary = true;
            buf.setLength(0);
        }

        public String feed(String text) {
            if (text == null || text.isEmpty()) {
                return "";
            }
            String combined = buf.toString() + text;
            buf.setLength(0);
            String lower = combined.toLowerCase();
            StringBuilder out = new StringBuilder();
            int i = 0;
            int len = combined.length();
            while (i < len) {
                if (inSpan) {
                    int close = lower.indexOf(CLOSE_TAG, i);
                    if (close == -1) {
                        int hold = maxPartialSuffix(lower, i, CLOSE_TAG);
                        if (hold > 0) {
                            buf.append(combined, len - hold, len);
                        }
                        return out.toString(); // span 内容丢弃
                    }
                    i = close + CLOSE_TAG.length();
                    inSpan = false;
                } else {
                    int open = lower.indexOf(OPEN_TAG, i);
                    if (open == -1) {
                        int hold = maxPartialSuffix(lower, i, OPEN_TAG);
                        if (isCompleteOpenTagAtBoundary(lower, len)) {
                            hold = Math.max(hold, OPEN_TAG.length());
                        }
                        if (hold > 0) {
                            if (len - hold > i) {
                                appendVisible(out, combined, i, len - hold);
                            }
                            buf.append(combined, len - hold, len);
                        } else {
                            appendVisible(out, combined, i, len);
                        }
                        return out.toString();
                    }
                    if (isBlockBoundary(lower, open) && isBlockOpenerSuffix(combined, open)) {
                        // 块边界开标签 + 后随换行 → 进入 span
                        if (open > i) {
                            appendVisible(out, combined, i, open);
                        }
                        i = open + OPEN_TAG.length();
                        inSpan = true;
                    } else if (open + OPEN_TAG.length() >= len && isBlockBoundary(lower, open)) {
                        // 尾部完整的块边界开标签：暂存，等下一片确认后随字符
                        if (open > i) {
                            appendVisible(out, combined, i, open);
                        }
                        buf.append(combined, open, len);
                        return out.toString();
                    } else {
                        // 非块边界出现：作为普通文本输出（对齐 hermes 不触发 span）
                        appendVisible(out, combined, i, open + OPEN_TAG.length());
                        i = open + OPEN_TAG.length();
                    }
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

        // ---- helpers ----

        private void appendVisible(StringBuilder out, String combined, int from, int to) {
            out.append(combined, from, to);
            updateBlockBoundary(combined.substring(from, to));
        }

        /** 开标签所在位置是否为块边界（行首或独立行，对齐 hermes _is_block_boundary） */
        private boolean isBlockBoundary(String lower, int idx) {
            if (idx == 0) {
                return atBlockBoundary;
            }
            int lastNewline = lower.lastIndexOf('\n', idx - 1);
            if (lastNewline == -1) {
                return atBlockBoundary && lower.substring(0, idx).trim().isEmpty();
            }
            return lower.substring(lastNewline + 1, idx).trim().isEmpty();
        }

        /** 开标签后是否紧跟换行（块级开始，对齐 hermes _has_block_opener_suffix） */
        private boolean isBlockOpenerSuffix(String combined, int idx) {
            int after = idx + OPEN_TAG.length();
            if (after >= combined.length()) {
                return false;
            }
            char c = combined.charAt(after);
            return c == '\n' || c == '\r';
        }

        /** combined 是否以完整的、位于块边界的开标签结尾 */
        private boolean isCompleteOpenTagAtBoundary(String lower, int len) {
            if (!lower.endsWith(OPEN_TAG)) {
                return false;
            }
            return isBlockBoundary(lower, len - OPEN_TAG.length());
        }

        private void updateBlockBoundary(String visible) {
            int lastNewline = visible.lastIndexOf('\n');
            if (lastNewline == -1) {
                atBlockBoundary = atBlockBoundary && visible.trim().isEmpty();
            } else {
                atBlockBoundary = visible.substring(lastNewline + 1).trim().isEmpty();
            }
        }

        private static int maxPartialSuffix(String lower, int from, String tag) {
            String tail = lower.substring(from);
            int max = Math.min(tail.length(), tag.length() - 1);
            for (int k = max; k >= 1; k--) {
                if (tag.startsWith(tail.substring(tail.length() - k))) {
                    return k;
                }
            }
            return 0;
        }
    }
}
