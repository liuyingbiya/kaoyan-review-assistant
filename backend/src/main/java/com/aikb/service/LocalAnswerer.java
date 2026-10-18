package com.aikb.service;

import com.aikb.model.KbChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 离线回答引擎：不调用任何大模型，只靠检索到的资料组织回答。
 *
 * 为什么需要它？
 *   1. 调用大模型要花钱（按 token 计费），演示环境被刷会烧额度
 *   2. 有些场景（内网、断网、考试）根本不能连外网
 *   3. 回答完全来自资料原文，**不可能编造** —— 这是比大模型更硬的保证
 *
 * 它做不到什么（要诚实）：
 *   · 不能跨段落推理，不能做多步计算
 *   · 换个说法问同一个问题，可能检索不到（没有语义理解）
 *   所以它是「检索式问答」，不是「生成式问答」。接上 API Key 就自动切回大模型模式。
 *
 * 回答怎么组织：
 *   问题 → 抽关键词 → 给每个句子打分（关键词覆盖 + 词频）→
 *   取分最高的 3~5 句，按原文顺序排列 → 前面加一句按问题类型选的引导语
 */
@Service
public class LocalAnswerer {

    /** 问题里的常见虚词，打分时忽略，否则会干扰排序 */
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "什么", "怎么", "怎样", "如何", "为什么", "哪些", "哪个", "请问", "一下",
            "这个", "那个", "可以", "应该", "以及", "如果", "是不是", "有没有",
            "说说", "介绍", "讲讲", "多少", "几个", "是否", "能否", "区别", "不同"
    ));

    /** 单句最长保留多少字（太长的句子读起来累，且往往掺了无关内容） */
    private static final int MAX_SENTENCE_LEN = 120;

    /** 最多摘几句 */
    private static final int MAX_SENTENCES = 5;

    /**
     * 生成回答并流式回调。
     *
     * @param question 用户问题
     * @param chunks   检索到的资料片段（按相关度从高到低）
     * @param onDelta  每生成一小段就回调一次，用于 SSE 推送
     */
    public void answer(String question, List<KbChunk> chunks, Consumer<String> onDelta) {
        List<String> sentences = new ArrayList<>();
        List<String> sources = new ArrayList<>();

        if (chunks != null) {
            for (KbChunk c : chunks) {
                String title = c.getDocTitle() == null ? "资料" : c.getDocTitle();
                for (String s : splitSentences(c.getContent())) {
                    String t = s.trim();
                    if (t.length() < 8 || t.length() > MAX_SENTENCE_LEN) {
                        continue;
                    }
                    sentences.add(t);
                    sources.add(title);
                }
            }
        }

        if (sentences.isEmpty()) {
            stream("资料里没有检索到相关内容。\n\n可以试试：\n- 换个说法再问一次\n- 点右上角「资料管理」把相关笔记导入进来", onDelta);
            return;
        }

        // ---------- 打分 ----------
        List<String> keys = keywords(question);
        double[] scores = new double[sentences.size()];
        for (int i = 0; i < sentences.size(); i++) {
            scores[i] = score(sentences.get(i), keys);
        }

        // 按分数取前 N 个下标
        Integer[] order = new Integer[sentences.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble((Integer i) -> scores[i]).reversed());

        List<Integer> picked = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Integer idx : order) {
            if (picked.size() >= MAX_SENTENCES) {
                break;
            }
            // 分数太低（0 分）说明跟问题无关，不要凑数
            if (scores[idx] <= 0) {
                break;
            }
            // 去掉几乎重复的句子（资料切片有 80 字重叠，会出现重复句）
            String norm = sentences.get(idx).replaceAll("\\s", "");
            if (seen.contains(norm)) {
                continue;
            }
            seen.add(norm);
            picked.add(idx);
        }

        if (picked.isEmpty()) {
            stream("资料里没有直接答案，但我检索到了这些相关内容：\n\n"
                    + firstSentences(sentences, 3)
                    + "\n建议换个更具体的问法，或者补充相关资料。", onDelta);
            return;
        }

        // 按原文顺序排，读起来像一段连贯的话，而不是乱序摘句
        picked.sort(Integer::compareTo);

        // ---------- 组织输出 ----------
        StringBuilder sb = new StringBuilder();
        sb.append(lead(question)).append("\n\n");
        int n = 1;
        for (Integer idx : picked) {
            sb.append(n++).append(". ").append(sentences.get(idx)).append("\n");
        }

        // 引用来源（去重）
        Set<String> src = new LinkedHashSet<>();
        for (Integer idx : picked) {
            src.add(sources.get(idx));
        }
        sb.append("\n—— 摘自：").append(String.join("、", src));
        sb.append("\n（离线检索模式：回答由资料原文摘录组成，未调用大模型）");

        stream(sb.toString(), onDelta);
    }

    /** 按问题类型选一句引导语，让回答读起来像是"回答"而不是"贴原文" */
    private String lead(String q) {
        if (q == null) {
            return "根据资料：";
        }
        if (q.contains("为什么") || q.contains("原因")) {
            return "原因主要有这几点：";
        }
        if (q.contains("区别") || q.contains("不同") || q.contains("对比")) {
            return "两者的区别如下：";
        }
        if (q.contains("怎么") || q.contains("如何") || q.contains("怎样") || q.contains("步骤")) {
            return "可以按下面的思路做：";
        }
        if (q.contains("条件") || q.contains("必要")) {
            return "需要满足的条件是：";
        }
        if (q.contains("多少") || q.contains("几")) {
            return "具体数值如下：";
        }
        return "根据资料：";
    }

    /** 抽关键词：按标点切分，去掉虚词，保留长度 >= 2 的片段 */
    List<String> keywords(String question) {
        List<String> out = new ArrayList<>();
        if (question == null) {
            return out;
        }
        String[] parts = question.split("[\\s，。！？、；：,.!?;:（）()\\[\\]【】\"'`]+");
        for (String p : parts) {
            String t = p.trim();
            if (t.length() < 2 || STOP_WORDS.contains(t)) {
                continue;
            }
            out.add(t);
        }
        if (out.isEmpty()) {
            // 全是虚词时，退化成按长度取前两段，避免零关键词
            for (String p : parts) {
                if (p.trim().length() >= 2) {
                    out.add(p.trim());
                }
            }
        }
        return out;
    }

    /** 句子得分 = 每个关键词出现次数之和；完整短语命中额外加权 */
    private double score(String sentence, List<String> keys) {
        double s = 0;
        for (String k : keys) {
            int c = countOccurrences(sentence, k);
            if (c > 0) {
                s += c * (1.0 + Math.min(k.length(), 6) * 0.3); // 长关键词更具体，权重更高
            }
        }
        return s;
    }

    private int countOccurrences(String text, String key) {
        if (key.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(key, idx)) >= 0) {
            count++;
            idx += key.length();
        }
        return count;
    }

    /**
     * 按句切分。
     * 中文句末标点 + 换行都算边界；标点保留在句子里（读起来完整）。
     */
    List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            cur.append(ch);
            if (ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == '\n') {
                String s = cur.toString().replaceAll("^[\\s#>\\-*]+", "").trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            String s = cur.toString().trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private String firstSentences(List<String> sentences, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, sentences.size()); i++) {
            sb.append("- ").append(sentences.get(i)).append("\n");
        }
        return sb.toString();
    }

    /** 按小块流式下发，模拟打字效果（前端本来就是边收边渲染） */
    private void stream(String text, Consumer<String> onDelta) {
        int step = 6;
        for (int i = 0; i < text.length(); i += step) {
            int end = Math.min(i + step, text.length());
            onDelta.accept(text.substring(i, end));
            try {
                Thread.sleep(8);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
