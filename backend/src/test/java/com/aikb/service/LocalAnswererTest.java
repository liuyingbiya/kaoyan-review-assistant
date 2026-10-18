package com.aikb.service;

import com.aikb.model.KbChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 离线回答引擎的单元测试。
 *
 * 为什么必须测：这套逻辑没有大模型兜底，排序错了用户就看到一段废话。
 * 而且线上试错成本高（每次要重新部署），所以关键行为在这里钉死：
 *   1. 中文长句必须能切出关键词（否则永远零命中）
 *   2. 问「死锁四个必要条件」必须命中操作系统资料里的那一句
 *   3. 被切片切断的碎片不能被摘进回答
 */
class LocalAnswererTest {

    private final LocalAnswerer answerer = new LocalAnswerer();

    private String run(String question, String... contents) {
        List<KbChunk> chunks = new ArrayList<>();
        for (String c : contents) {
            KbChunk k = new KbChunk();
            k.setContent(c);
            k.setDocTitle("操作系统核心考点");
            chunks.add(k);
        }
        StringBuilder sb = new StringBuilder();
        answerer.answer(question, chunks, sb::append);
        return sb.toString();
    }

    @Test
    void 中文长句要能切出关键词() {
        List<String> keys = answerer.keywords("死锁的四个必要条件是什么？");
        assertTrue(keys.contains("死锁"), "应该切出「死锁」，实际: " + keys);
        assertTrue(keys.contains("条件"), "应该切出「条件」，实际: " + keys);
        assertFalse(keys.contains("什么"), "虚词「什么」应被过滤，实际: " + keys);
        // 不能把整句当成唯一关键词
        assertTrue(keys.size() > 3, "关键词应该多于 3 个，实际: " + keys);
    }

    @Test
    void 问死锁要命中对应句子() {
        String content = "## 死锁\n\n死锁的四个必要条件：互斥、占有并等待、不可剥夺、循环等待。"
                + "四个条件同时成立才可能死锁，破坏其中任意一个即可预防死锁。\n\n"
                + "## 银行家算法\n\n银行家算法在分配前判断系统是否仍处于安全状态。";
        String out = run("死锁的四个必要条件是什么？", content);

        assertTrue(out.contains("互斥"), "回答里应包含「互斥」，实际:\n" + out);
        assertTrue(out.contains("摘自"), "应带来源，实际:\n" + out);
        assertTrue(out.contains("离线检索模式"), "应标注离线模式，实际:\n" + out);
        assertFalse(out.contains("资料里没有直接答案"), "不应该走兜底，实际:\n" + out);
        // 回答的第一条要点应该就是最相关的那句
        assertTrue(out.contains("1. 死锁的四个必要条件"), "第一条要点应是结论句，实际:\n" + out);
    }

    @Test
    void 被切断的碎片不该出现在回答里() {
        String content = "llocation），反复直到所有进程都能完成。\n\n"
                + "死锁的四个必要条件：互斥、占有并等待、不可剥夺、循环等待。";
        String out = run("死锁的四个必要条件是什么？", content);
        assertFalse(out.contains("llocation"), "以半截英文开头的碎片应被过滤，实际:\n" + out);
    }

    @Test
    void 完全无关的问题走兜底而不是硬编() {
        String content = "死锁的四个必要条件：互斥、占有并等待、不可剥夺、循环等待。";
        String out = run("英语作文怎么开头？", content);
        assertTrue(out.contains("资料里没有直接答案") || out.contains("摘自"),
                "要么明确说没有，要么给出相关内容，不能死撑，实际:\n" + out);
    }

    @Test
    void 空资料要有明确提示() {
        StringBuilder sb = new StringBuilder();
        answerer.answer("随便问问", new ArrayList<>(), sb::append);
        assertTrue(sb.toString().contains("没有检索到相关内容"), "空资料时应提示，实际: " + sb);
    }

    @Test
    void 句末标点要保留_句子要完整() {
        List<String> s = answerer.splitSentences("第一句话。第二句话！第三句？");
        assertEquals(Arrays.asList("第一句话。", "第二句话！", "第三句？"), s);
    }

    @Test
    void 标题行不算句子_加粗标记要被清掉() {
        String content = "## 死锁四个必要条件\n\n死锁的四个必要条件：**互斥、占有并等待、不可剥夺、循环等待**。";
        String out = run("死锁的四个必要条件是什么？", content);

        assertFalse(out.contains("##"), "不该出现 Markdown 标题符，实际:\n" + out);
        assertFalse(out.contains("**"), "不该出现加粗标记，实际:\n" + out);
        // 标题「死锁四个必要条件」不应作为独立一条要点出现
        assertFalse(out.contains("1. 死锁四个必要条件\n"), "标题不该被当成句子，实际:\n" + out);
        assertTrue(out.contains("互斥、占有并等待"), "正文句子应该在，实际:\n" + out);
    }

    @Test
    void 列表序号要被清掉_近似重复要去重() {
        String content = "四个同时成立才可能死锁，破坏任意一个即可预防。\n"
                + "四个同时成立才可能死锁，破坏任一即可预防。\n"
                + "3. 证明题主要集中在：线性相关性的证明、秩的不等式、可对角化的判定。";
        String out = run("死锁为什么四个条件同时成立才可能发生？", content);

        assertFalse(out.contains("3. 证明题"), "行首序号应被清掉，实际:\n" + out);
        // 「四个同时成立才可能死锁…」两条近似重复，只能出现一条
        int count = 0;
        for (String line : out.split("\n")) {
            if (line.contains("才可能死锁")) {
                count++;
            }
        }
        assertEquals(1, count, "近似重复应去重，实际:\n" + out);
    }
}
