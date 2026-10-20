package com.aikb.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 关键词分析：把用户的一整句话拆成几个「有检索价值」的词。
 *
 * 用途有两个：
 *   1. 展示给用户看 —— 让他知道系统是怎么理解这个问题的（关键词错了，答案自然不对）
 *   2. 兜底检索 —— 整句拿去查全文索引经常查不到（索引按词切，整句匹配不上），
 *      用关键词再查一次，命中率明显提高
 *
 * 为什么不用分词库（IK / jieba）：
 *   这个项目只有中文 + 少量英文，规则足够用；
 *   引一个分词库要多几百 KB 依赖，还得配词典，收益不成正比。
 *   规则的办法是「滑窗 + 停用词 + 长度权重」，对「死锁的四个必要条件」这种
 *   复习类提问足够准确。
 */
@Service
public class KeywordAnalyzer {

    /** 问句里的虚词/疑问词，出现就丢掉 */
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "什么", "怎么", "怎样", "如何", "为什么", "哪些", "哪个", "哪几种", "请问", "一下",
            "这个", "那个", "可以", "应该", "以及", "如果", "是不是", "有没有", "多少",
            "说说", "介绍", "讲讲", "是否", "能否", "区别", "不同", "问题", "请问一下",
            "帮我", "我想", "知道", "告诉", "一下下", "的是", "是指", "都有", "哪些些"
    ));

    /** 单个关键词最长保留几个字；（全文索引是 ngram 切的，长一点反而更精确） */
    private static final int MAX_KEYWORD_LEN = 10;

    /** 一次最多给几个关键词 */
    private static final int MAX_KEYWORDS = 5;

    /**
     * 名词短语的连接词。
     *
     * 「死锁的四个必要条件」按「的」切成「死锁」「四个必要条件」才是有检索价值的词；
     * 不切就会得到「死锁的」「的四」「四个必」这种垃圾碎片。
     *
     * ⚠️ 只放结构助词（的/地/得/和/与/及/或），不要放「对/于/在/把/将」这类介词 ——
     * 「矩阵可对角化」会被「对」劈成「矩阵可」「角化」，反而更糟。
     */
    private static final String CONNECTORS = "[的地得和与及或]";

    /**
     * 提取关键词。
     *
     * @param question    用户问题
     * @param subjectName 当前科目名（如「操作系统」），出现在问题里时作为强关键词保留
     * @return 1~5 个关键词，按重要性排序；提取不到时返回空列表
     */
    public List<String> extract(String question, String subjectName) {
        List<String> out = new ArrayList<>();
        if (question == null || question.trim().isEmpty()) {
            return out;
        }

        // 1) 先删掉疑问词，避免「什么」「怎么」混进后面的切分
        String cleaned = question;
        for (String w : STOP_WORDS) {
            cleaned = cleaned.replace(w, " ");
        }

        Set<String> seen = new LinkedHashSet<>();
        // 2) 科目名单独保留：问「操作系统的死锁」时，「操作系统」本身就是强关键词
        if (subjectName != null && !subjectName.isEmpty() && question.contains(subjectName)) {
            seen.add(subjectName);
        }

        // 3) 先按标点切段，每段再按连接词切成名词短语
        String[] parts = cleaned.split("[\\s，。！？、；：,.!?;:（）()\\[\\]【】\"'`·/|]+");
        for (String raw : parts) {
            String p = raw.trim();
            if (p.isEmpty()) {
                continue;
            }
            // 纯数字（408、2026）与英文术语（Spring、RAG、MATCH）整段保留
            if (p.matches("\\d{2,4}")) {
                seen.add(p);
                continue;
            }
            if (p.matches("[A-Za-z][A-Za-z0-9+#.\\-]{1,}")) {
                seen.add(p);
                continue;
            }
            for (String chunk : p.split(CONNECTORS)) {
                addChunk(chunk, seen);
            }
        }

        // 4) 截断（至少要有 1 个词）
        for (String s : seen) {
            if (out.size() >= MAX_KEYWORDS) {
                break;
            }
            String t = s.trim();
            if (t.length() >= 2 && !STOP_WORDS.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** 处理一个名词短语：短的就直接留，太长的按 4 字滑窗切 */
    private void addChunk(String chunk, Set<String> seen) {
        String c = chunk == null ? "" : chunk.trim();
        // 去掉首尾的虚字：「四个必要条件是」→「四个必要条件」，「的是」这类残留也一并清掉
        c = trimEdges(c);
        if (c.length() < 2) {
            return;
        }
        if (c.length() <= MAX_KEYWORD_LEN) {
            seen.add(c);
            return;
        }
        // 超长（>10 字）说明还是整句，切 4 字滑窗，最多取 3 个
        int added = 0;
        for (int i = 0; i + 4 <= c.length() && added < 3; i++) {
            String g = c.substring(i, i + 4);
            if (!STOP_WORDS.contains(g)) {
                seen.add(g);
                added++;
            }
        }
    }

    /** 反复剥掉首尾的虚字（"是/的/了/吗…"），只保留实词 */
    private String trimEdges(String s) {
        String trimChars = "是的地得了吗呢吧啊哦嗯么呀嘛";
        int start = 0;
        int end = s.length();
        while (start < end && trimChars.indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && trimChars.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(start, end).trim();
    }

    /** 关键词拼成检索用的查询串 */
    public String toQuery(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return "";
        }
        return String.join(" ", keywords);
    }
}
