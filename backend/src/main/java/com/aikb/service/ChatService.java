package com.aikb.service;

import com.aikb.mapper.ChatMapper;
import com.aikb.mapper.SubjectMapper;
import com.aikb.model.ChatMsg;
import com.aikb.model.ChatSession;
import com.aikb.model.KbChunk;
import com.aikb.model.Subject;
import com.aikb.model.WebResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对话编排：检索 → 拼 Prompt → 流式调用模型 → 落库。
 *
 * 为什么要线程池？
 *   LlmClient 读大模型响应是阻塞的（要一直读到流结束）。
 *   直接在 Controller 线程里做，几个并发就把 Tomcat 线程池占满。
 *   所以 Controller 立刻返回 SseEmitter，真正的流式任务丢给线程池跑。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatMapper chatMapper;
    private final RetrievalService retrieval;
    private final LlmClient llm;
    private final SubjectMapper subjectMapper;
    /** 离线回答引擎：没配 API Key 时用它，不消耗任何额度 */
    private final LocalAnswerer localAnswerer;
    /** 联网检索：题库没命中时的兜底 */
    private final WebSearchService webSearch;
    /** 关键词分析：既用于展示，也用于检索兜底 */
    private final KeywordAnalyzer keywordAnalyzer;

    /** 使用范围声明：程序只做考研咨询，其他问题会被这条声明挡一下 */
    public static final String SCOPE_NOTICE = "本程序只提供考研方面咨询，请不要提问无关信息。";

    /**
     * 判断「考研相关」的关键词。
     *
     * 为什么需要：题库查不到时我们会去联网，但如果不先判断范围，
     * 用户问「今天北京天气」也会真去搜天气 —— 这和程序声明的用途矛盾。
     * 所以：题库没命中时，先看问题里有没有这些词，没有就直接拒答。
     */
    private static final String[] SCOPE_MARKERS = {
            "考研", "真题", "复习", "考试", "初试", "复试", "专业课", "公共课", "上岸",
            "分数线", "大纲", "题型", "考点", "笔记", "背诵", "刷题", "模拟",
            "政治", "英语", "数学", "高数", "线代", "概率", "408",
            "数据结构", "计算机组成", "操作系统", "计算机网络", "数据库"
    };

    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    public ChatService(ChatMapper chatMapper, RetrievalService retrieval, LlmClient llm,
                       SubjectMapper subjectMapper, LocalAnswerer localAnswerer,
                       WebSearchService webSearch, KeywordAnalyzer keywordAnalyzer) {
        this.chatMapper = chatMapper;
        this.retrieval = retrieval;
        this.llm = llm;
        this.subjectMapper = subjectMapper;
        this.localAnswerer = localAnswerer;
        this.webSearch = webSearch;
        this.keywordAnalyzer = keywordAnalyzer;
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    public SseEmitter ask(Integer sessionId, String subjectCode, String question) {
        SseEmitter emitter = new SseEmitter(180_000L);

        pool.execute(() -> {
            try {
                // ---------- 1. 会话 ----------
                ChatSession session = sessionId == null ? null : chatMapper.findSession(sessionId);
                if (session == null) {
                    session = new ChatSession();
                    session.setSubjectCode(subjectCode);
                    String title = question.length() > 20 ? question.substring(0, 20) : question;
                    session.setTitle(title);
                    chatMapper.insertSession(session);
                }

                // ---------- 2. 关键词分析 ----------
                // 先分析再发 meta：前端拿到关键词可以立刻展示「系统是这样理解你的问题的」
                Subject subject = subjectMapper.findByCode(subjectCode);
                String subjectName = subject == null ? null : subject.getName();
                List<String> keywords = keywordAnalyzer.extract(question, subjectName);

                send(emitter, "meta", obj(
                        "sessionId", session.getId(),
                        "subjectCode", subjectCode,
                        "model", llm.modelName(),
                        "mock", llm.isMock(),
                        "keywords", keywords));

                // ---------- 3. 用户提问落库 ----------
                ChatMsg userMsg = new ChatMsg();
                userMsg.setSessionId(session.getId());
                userMsg.setRole("user");
                userMsg.setContent(question);
                chatMapper.insertMsg(userMsg);

                // ---------- 4. 检索（限定科目）----------
                // 先用原句查；查不到再用关键词查一遍。
                // 原因：全文索引按词切分，「死锁的四个必要条件是什么」整句几乎匹配不上，
                // 换成「死锁 必要条件」反而能命中。
                List<KbChunk> hits = retrieval.retrieve(subjectCode, question);
                boolean keywordFallback = false;
                if (hits.isEmpty() && !keywords.isEmpty()) {
                    hits = retrieval.retrieve(subjectCode, keywordAnalyzer.toQuery(keywords));
                    keywordFallback = !hits.isEmpty();
                }
                List<String> contexts = new ArrayList<>();
                List<Map<String, Object>> citations = new ArrayList<>();
                for (KbChunk c : hits) {
                    contexts.add(c.getContent());
                    Map<String, Object> item = new HashMap<>();
                    item.put("docId", c.getDocId());
                    item.put("docTitle", c.getDocTitle());
                    item.put("seq", c.getSeq());
                    item.put("score", c.getScore());
                    String text = c.getContent();
                    item.put("content", text.length() > 200 ? text.substring(0, 200) + "…" : text);
                    citations.add(item);
                }

                // ---------- 4. 拼 Prompt ----------
                List<Map<String, String>> messages = new ArrayList<>();
                messages.add(LlmClient.msg("system", systemPrompt(subjectCode, contexts)));

                // 最近 3 轮历史，让"那它呢"这种追问能接上
                List<ChatMsg> history = chatMapper.listMsgs(session.getId());
                int from = Math.max(0, history.size() - 6);
                for (int i = from; i < history.size(); i++) {
                    ChatMsg m = history.get(i);
                    if ("user".equals(m.getRole()) || "assistant".equals(m.getRole())) {
                        messages.add(LlmClient.msg(m.getRole(), m.getContent()));
                    }
                }

                // ---------- 5. 生成回答 ----------
                // 两条路径：
                //   · 配了 API Key → 调 DeepSeek 流式生成（会消耗 token）
                //   · 没配 → 离线回答引擎；如果题库里没有能答的句子，再联网检索兜底
                StringBuilder answer = new StringBuilder();
                java.util.function.Consumer<String> sink = delta -> {
                    answer.append(delta);
                    send(emitter, "delta", obj("text", delta));
                };
                if (llm.isMock()) {
                    LocalAnswerer.Answer local = localAnswerer.build(question, hits);
                    String text = local.text;
                    boolean answered = local.matched;
                    // 题库优先、联网兜底：只有「本地没命中」时才去问搜索引擎
                    if (!answered && inScope(question, subjectName)) {
                        List<WebResult> webs = webSearch.search(question, 5);
                        if (!webs.isEmpty()) {
                            text = composeWebAnswer(webs);
                            answered = true;
                            for (WebResult w : webs) {
                                Map<String, Object> item = new HashMap<>();
                                item.put("docTitle", w.getTitle());
                                item.put("url", w.getUrl());
                                item.put("content", w.getSnippet());
                                item.put("web", true);
                                citations.add(item);
                            }
                        }
                    }
                    if (!answered) {
                        text = "这个问题超出了本程序的范围，我不回答。\n\n" + SCOPE_NOTICE
                                + "\n\n你可以问：" + (subjectName == null ? "考研" : subjectName)
                                + "相关的概念、公式、题型或复习方法。";
                    }
                    localAnswerer.emit(text, sink);
                } else {
                    llm.streamChat(messages, sink, contexts);
                }

                // ---------- 6. 引用 + 落库 ----------
                send(emitter, "citations", obj("items", citations));
                ChatMsg aiMsg = new ChatMsg();
                aiMsg.setSessionId(session.getId());
                aiMsg.setRole("assistant");
                aiMsg.setContent(answer.toString());
                aiMsg.setCitations(JSON.writeValueAsString(citations));
                chatMapper.insertMsg(aiMsg);

                send(emitter, "done", obj("chars", answer.length()));
                emitter.complete();

            } catch (Exception e) {
                log.error("流式问答失败", e);
                try {
                    send(emitter, "error", obj("message",
                            e.getMessage() == null ? "未知错误" : e.getMessage()));
                } catch (Exception ignore) {
                    // emitter 已关闭
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 系统提示词。
     * 「只能依据资料回答、不许编造」这条必须写死 —— 这是 RAG 压住幻觉的核心，
     * 也决定了模型在没检索到资料时会老实说"资料里没有"，而不是胡编。
     */
    private String systemPrompt(String subjectCode, List<String> contexts) {
        Subject subject = subjectMapper.findByCode(subjectCode);
        String scope = subject == null ? "该科目" : subject.getName();

        StringBuilder sb = new StringBuilder();
        sb.append("你是考研「").append(scope).append("」的复习助手。\n");
        sb.append("回答规则：\n");
        sb.append("1. 只能依据下面提供的【资料】回答，不要使用资料以外的知识；\n");
        sb.append("2. 如果资料里没有答案，直接回答「资料里没有提到这个问题」，不要猜测；\n");
        sb.append("3. 用中文回答，先说结论再分点解释，控制在 300 字以内；\n");
        sb.append("4. 引用资料时用「资料1」「资料2」标注出处；\n");
        sb.append("5. 如果这是需要推导的题目，把关键步骤写出来，不要只给答案。\n\n");
        if (contexts.isEmpty()) {
            sb.append("【资料】\n（本次没有检索到任何资料）\n");
        } else {
            sb.append("【资料】\n");
            for (int i = 0; i < contexts.size(); i++) {
                sb.append("资料").append(i + 1).append("：")
                        .append(contexts.get(i)).append("\n\n");
            }
        }
        return sb.toString();
    }

    /**
     * 把联网检索结果组织成可读回答。
     *
     * 注意用词：这里不是"AI 回答"，是"搜索结果摘要"。
     * 不调大模型时，我们能保证的只有「内容是真实的、链接是能点开的」，
     * 不能保证「像人一样组织过」——这一点在结尾括号里写明，避免误导。
     */
    private String composeWebAnswer(List<WebResult> webs) {
        StringBuilder sb = new StringBuilder();
        sb.append("题库里没有，我联网查了一下：\n\n");
        int i = 1;
        for (WebResult w : webs) {
            sb.append(i++).append(". ").append(w.getTitle()).append("\n");
            String s = w.getSnippet();
            if (s != null && !s.isEmpty()) {
                sb.append("   ").append(s).append("\n");
            }
            sb.append("   ").append(w.getUrl()).append("\n");
        }
        sb.append("\n（联网检索模式：以上为搜索结果摘要，未调用大模型；点开引用来源可看原文）");
        return sb.toString();
    }

    /**
     * 这个问题是否属于「考研范围」。
     *
     * 判据很朴素：问题里出现当前科目名，或出现考研/学科相关的词，就算在范围内。
     * 宁可宽松一点（漏判的代价是回答一个无关问题），也不能太严 ——
     * 误判「中值定理怎么想」为越界会直接影响正常使用。
     */
    private boolean inScope(String question, String subjectName) {
        if (question == null) {
            return false;
        }
        if (subjectName != null && question.contains(subjectName)) {
            return true;
        }
        for (String m : SCOPE_MARKERS) {
            if (question.contains(m)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static void send(SseEmitter emitter, String type, Map<String, Object> data) {
        try {
            Map<String, Object> event = new HashMap<>(data);
            event.put("type", type);
            emitter.send(SseEmitter.event().data(JSON.writeValueAsString(event)));
        } catch (Exception e) {
            // 用户切走了导致连接断开是常态，不当错误抛
            log.debug("SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }
}
