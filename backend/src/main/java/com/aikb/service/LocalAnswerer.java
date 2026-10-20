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
     * 回答结果。
     *
     * matched = false 表示「资料里没找到能回答的句子」，调用方可以据此决定
     * 要不要再联网搜一次（题库优先、外部兜底）。
     */
    public static class Answer {
        public final String text;
        public final boolean matched;

        public Answer(String text, boolean matched) {
            this.text = text;
            this.matched = matched;
        }
    }

    /**
     * 只用本地资料组织回答（不联网、不调模型）。
     *
     * @param question 用户问题
     * @param chunks   检索到的资料片段（按相关度从高到低）
     * @return 回答文本 + 是否命中（没命中时调用方可以再走联网兜底）
     */
    public Answer build(String question, List<KbChunk> chunks) {
        List<String> sentences = new ArrayList<>();
        List<String> sources = new ArrayList<>();

        if (chunks != null) {
            for (KbChunk c : chunks) {
                String title = c.getDocTitle() == null ? "资料" : c.getDocTitle();
                for (String s : splitSentences(c.getContent())) {
                    // 清掉 Markdown 标记（**加粗**、`代码`），回答里不该出现这些符号
                    String t = s.replace("**", "").replace("`", "").trim();
                    // 太短没信息量、太长掺无关内容、被切断的碎片，都丢掉
                    if (!usable(t) || t.length() > MAX_SENTENCE_LEN) {
                        continue;
                    }
                    sentences.add(t);
                    sources.add(title);
                }
            }
        }

        if (sentences.isEmpty()) {
            return new Answer("资料里没有检索到相关内容。\n\n可以试试：\n- 换个说法再问一次\n"
                    + "- 点右上角「资料管理」把相关笔记导入进来", false);
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
        List<String> pickedTexts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Integer idx : order) {
            if (picked.size() >= MAX_SENTENCES) {
                break;
            }
            // 分数太低（0 分）说明跟问题无关，不要凑数
            if (scores[idx] <= 0) {
                break;
            }
            String text = sentences.get(idx);
            // 去掉几乎重复的句子（资料切片有 80 字重叠，会出现重复句）
            String norm = text.replaceAll("\\s", "");
            if (seen.contains(norm)) {
                continue;
            }
            // 近似重复也要去掉：一句包含另一句时只留长的
            boolean dup = false;
            for (String t2 : pickedTexts) {
                if (similar(text, t2)) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                continue;
            }
            seen.add(norm);
            pickedTexts.add(text);
            picked.add(idx);
        }

        if (picked.isEmpty()) {
            return new Answer("资料里没有直接答案，但我检索到了这些相关内容：\n\n"
                    + firstSentences(sentences, 3)
                    + "\n建议换个更具体的问法，或者补充相关资料。", false);
        }

        // ---------- 组织输出 ----------
        // picked 已按得分从高到低排序：第一句就是最贴近问题的，直接当"结论"放在最前面，
        // 后面再按得分列要点。这样即使原文顺序里那句在后面，回答也先给最关键的。
        StringBuilder sb = new StringBuilder();
        sb.append(lead(question)).append("\n\n");
        for (int i = 0; i < picked.size(); i++) {
            sb.append(i + 1).append(". ").append(sentences.get(picked.get(i))).append("\n");
        }

        // 引用来源（去重）
        Set<String> src = new LinkedHashSet<>();
        for (Integer idx : picked) {
            src.add(sources.get(idx));
        }
        sb.append("\n—— 摘自：").append(String.join("、", src));
        sb.append("\n（离线检索模式：回答由资料原文摘录组成，未调用大模型）");

        return new Answer(sb.toString(), true);
    }

    /** 按问题类型选一句引导语，让回答读起来像是"回答"而不是"贴原文" */
    private String lead(String q) {
        if (q == null) {
            return "根据资料：";
        }
        if (q.contains("区别") || q.contains("不同") || q.contains("对比")) {
            return "两者的区别如下：";
        }
        // 「哪几种 / 哪些」要在「几」之前判断，否则会掉进"具体数值"分支
        if (q.contains("哪些") || q.contains("哪几种") || q.contains("哪类") || q.contains("几种")) {
            return "主要有这几类：";
        }
        if (q.contains("为什么") || q.contains("原因")) {
            return "原因主要有这几点：";
        }
        if (q.contains("怎么") || q.contains("如何") || q.contains("怎样") || q.contains("步骤")) {
            return "可以按下面的思路做：";
        }
        if (q.contains("条件") || q.contains("必要")) {
            return "需要满足的条件是：";
        }
        if (q.contains("多少") || q.contains("几分") || q.contains("几个")) {
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
        Set<String> seen = new LinkedHashSet<>();
        for (String p : parts) {
            String t = p.trim();
            if (t.length() < 2) {
                continue;
            }
            if (!STOP_WORDS.contains(t)) {
                seen.add(t);
            }
            // 中文没有空格，一个问题往往是一整串字（如「死锁的四个必要条件是什么」）。
            // 如果只把它当成一个词，几乎不可能在句子里原样出现，得分恒为 0。
            // 所以再切出 2 字和 3 字的滑窗（死锁 / 锁的 / 四个 / 条件 …），
            // 这样只要句子里出现了「死锁」「条件」就能命中。
            if (isAllCjk(t)) {
                for (int n = 2; n <= 3; n++) {
                    for (int i = 0; i + n <= t.length(); i++) {
                        String g = t.substring(i, i + n);
                        if (!STOP_WORDS.contains(g)) {
                            seen.add(g);
                        }
                    }
                }
            }
        }
        out.addAll(seen);
        return out;
    }

    /** 是否全为汉字（用于判断要不要做中文切词） */
    private boolean isAllCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x4E00 || c > 0x9FA5) {
                return false;
            }
        }
        return true;
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
                String before = cur.toString();
                // 以 # 开头的是 Markdown 标题，不是句子 —— 摘进回答会变成孤零零的一行
                if (!before.trim().startsWith("#")) {
                    String raw = cleanLine(before);
                    if (!raw.isEmpty()) {
                        out.add(raw);
                    }
                }
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            String before = cur.toString();
            if (!before.trim().startsWith("#")) {
                String raw = cleanLine(before);
                if (!raw.isEmpty()) {
                    out.add(raw);
                }
            }
        }
        return out;
    }

    /**
     * 清掉行首的 Markdown 与列表标记。
     *
     * 不处理会出现这种句子：「3. 证明题主要集中在：…」
     * —— 序号被当成正文一起摘进回答，读起来像格式错乱。
     */
    private String cleanLine(String s) {
        String t = s.trim();
        t = t.replaceAll("^[#>{|]+\\s*", "");            // 标题 / 引用 / 表格符
        t = t.replaceAll("^(?:[-*•]+|\\d+[.、)])\\s*", ""); // 无序列表 / 有序列表
        return t.trim();
    }

    /**
     * 两句是否近似重复。
     *
     * 资料切片有 80 字重叠，同一句话会以细微差别出现两次，例如：
     *   「四个同时成立才可能死锁，破坏任意一个即可预防。」
     *   「四个同时成立才可能死锁，破坏任一即可预防。」
     * 只判断"包含"抓不住这种（两者互不包含），所以用字符二元组的 Jaccard 相似度。
     */
    private boolean similar(String a, String b) {
        String x = normalize(a);
        String y = normalize(b);
        if (x.length() < 6 || y.length() < 6) {
            return false;
        }
        if (x.contains(y) || y.contains(x)) {
            int min = Math.min(x.length(), y.length());
            int max = Math.max(x.length(), y.length());
            if (min * 1.0 / max >= 0.5) {
                return true;
            }
        }
        return jaccard(x, y) >= 0.6;
    }

    /** 去掉标点与空白，统一小写，便于比较 */
    private String normalize(String s) {
        return s.replaceAll("[\\s，。、；：！？（）《》\"'`*·—…]", "").toLowerCase();
    }

    /** 字符二元组 Jaccard 相似度（0~1） */
    private double jaccard(String a, String b) {
        Set<String> sa = bigrams(a);
        Set<String> sb = bigrams(b);
        if (sa.isEmpty() || sb.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(sa);
        inter.retainAll(sb);
        Set<String> union = new HashSet<>(sa);
        union.addAll(sb);
        return inter.size() * 1.0 / union.size();
    }

    private Set<String> bigrams(String s) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 2 <= s.length(); i++) {
            out.add(s.substring(i, i + 2));
        }
        return out;
    }

    /**
     * 过滤掉「被切断的碎片」。
     *
     * 为什么需要：文档切片是每 400 字一刀，切点可能落在一个词中间，
     * 于是片段里会出现以半个英文单词（llocation）、右括号开头的句子。
     * 这种句子放进回答里很难看，也说明它本身不完整。
     */
    private boolean usable(String s) {
        if (s.length() < 8) {
            return false;
        }
        char c0 = s.charAt(0);
        if (c0 == '）' || c0 == ')' || c0 == '】' || c0 == '」' || c0 == '、'
                || c0 == '，' || c0 == '。' || c0 == '；' || c0 == '：') {
            return false;
        }
        // 小写字母开头 → 多半是从中间被切开的英文单词
        if (c0 >= 'a' && c0 <= 'z') {
            return false;
        }
        // 至少要有一点汉字，纯符号/纯数字的片段没意义
        boolean hasCjk = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FA5) {
                hasCjk = true;
                break;
            }
        }
        return hasCjk || s.length() >= 20;
    }

    private String firstSentences(List<String> sentences, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, sentences.size()); i++) {
            sb.append("- ").append(sentences.get(i)).append("\n");
        }
        return sb.toString();
    }

    /** 便捷方法：生成回答并直接流式回调（供测试与旧调用方使用） */
    public void answer(String question, List<KbChunk> chunks, Consumer<String> onDelta) {
        emit(build(question, chunks).text, onDelta);
    }

    /** 按小块流式下发，模拟打字效果（前端本来就是边收边渲染） */
    public void emit(String text, Consumer<String> onDelta) {
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
