package com.aikb.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 关键词分析的单元测试。
 *
 * 这套规则全靠启发式，不钉死行为很容易「改一处坏一处」，
 * 所以把复习场景里最典型的问法都写成断言。
 */
class KeywordAnalyzerTest {

    private final KeywordAnalyzer analyzer = new KeywordAnalyzer();

    @Test
    void 按连接词切出名词短语() {
        List<String> k = analyzer.extract("死锁的四个必要条件是什么？", "操作系统");
        assertTrue(k.contains("四个必要条件"), "应切出「四个必要条件」，实际: " + k);
        assertFalse(k.contains("死锁的"), "不该出现半截碎片，实际: " + k);
        assertFalse(k.contains("什么"), "疑问词应被去掉，实际: " + k);
    }

    @Test
    void 问题里出现科目名时保留科目名() {
        List<String> k = analyzer.extract("操作系统里的死锁怎么避免", "操作系统");
        assertEquals("操作系统", k.get(0), "科目名应排第一，实际: " + k);
    }

    @Test
    void 中等长度的整句整段保留() {
        List<String> k = analyzer.extract("考研英语新题型有哪几种", "英语一");
        assertTrue(k.stream().anyMatch(s -> s.contains("英语新题型")),
                "应保留「考研英语新题型」，实际: " + k);
        assertFalse(k.stream().anyMatch(s -> s.contains("哪几种")), "疑问词应去掉，实际: " + k);
    }

    @Test
    void 英文术语与数字保留() {
        List<String> k = analyzer.extract("408 的 Spring Boot 和 MyBatis 区别", "数据结构");
        assertTrue(k.stream().anyMatch(s -> s.equals("408")), "数字应保留，实际: " + k);
        assertTrue(k.stream().anyMatch(s -> s.contains("Spring")), "英文术语应保留，实际: " + k);
    }

    @Test
    void 不重复且不超过五个() {
        List<String> k = analyzer.extract("死锁 死锁 死锁 条件 条件 必要条件", "操作系统");
        assertTrue(k.size() <= 5, "最多 5 个，实际: " + k);
        assertEquals(k.size(), k.stream().distinct().count(), "不该有重复，实际: " + k);
    }

    @Test
    void 超长句子要切成四字滑窗() {
        String longText = "这一道题目考察的是操作系统里进程调度算法的时间片轮转与优先级结合的综合分析";
        List<String> k = analyzer.extract(longText, null);
        assertFalse(k.isEmpty(), "应至少抽出一个关键词");
        assertTrue(k.stream().allMatch(s -> s.length() <= 10), "关键词不该超长，实际: " + k);
    }

    @Test
    void 空输入返回空列表() {
        assertTrue(analyzer.extract(null, "操作系统").isEmpty());
        assertTrue(analyzer.extract("   ", "操作系统").isEmpty());
        assertTrue(analyzer.extract("？", "操作系统").isEmpty());
    }

    @Test
    void 介词不该把词劈开() {
        List<String> k = analyzer.extract("矩阵可对角化的条件是什么？", "线性代数");
        assertTrue(k.stream().anyMatch(s -> s.contains("矩阵可对角化")),
                "「对」不是连接词，不该把词劈开，实际: " + k);
        assertFalse(k.contains("角化"), "不该出现半截词，实际: " + k);
    }

    @Test
    void 关键词能拼成查询串() {
        assertEquals("死锁 条件", analyzer.toQuery(Arrays.asList("死锁", "条件")));
        assertEquals("", analyzer.toQuery(java.util.Collections.emptyList()));
    }
}
