package cn.zcj.aether.domain.agent.service.retrieval.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CjkBigramTest {

    @Test
    void cjkTextBecomesBigramStream() {
        assertEquals("用户 户偏 偏好", CjkBigram.bigram("用户偏好"));
    }

    @Test
    void mixedCjkAndLatin() {
        String out = CjkBigram.bigram("用户偏好VSCode编辑器");
        assertEquals("用户 户偏 偏好 vscode 编辑 辑器", out);
    }

    @Test
    void singleCjkCharKeepsUnigram() {
        assertEquals("搜", CjkBigram.bigram("搜"));
    }

    @Test
    void latinWordsAndDigitsKept() {
        assertEquals("java 17 lts", CjkBigram.bigram("Java 17 LTS"));
    }

    @Test
    void punctuationIsSeparator() {
        assertEquals("记忆 机制", CjkBigram.bigram("记忆、机制！"));
    }

    @Test
    void nullAndBlankSafe() {
        assertEquals("", CjkBigram.bigram(null));
        assertEquals("", CjkBigram.bigram("   "));
    }

    @Test
    void queryAndContentShareVocabulary() {
        // 词法召回的关键性质：相同表述 → 相同 token 集
        String q = CjkBigram.bigram("分布式限流算法");
        String d = CjkBigram.bigram("分布式限流算法实现");
        for (String token : q.split(" ")) {
            assertTrue(d.contains(token), "查询 token 应在内容 token 流中: " + token);
        }
    }
}
