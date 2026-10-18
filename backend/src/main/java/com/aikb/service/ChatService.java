package com.aikb.service;

import com.aikb.mapper.ChatMapper;
import com.aikb.mapper.SubjectMapper;
import com.aikb.model.ChatMsg;
import com.aikb.model.ChatSession;
import com.aikb.model.KbChunk;
import com.aikb.model.Subject;
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

    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    public ChatService(ChatMapper chatMapper, RetrievalService retrieval, LlmClient llm,
                       SubjectMapper subjectMapper, LocalAnswerer localAnswerer) {
        this.chatMapper = chatMapper;
        this.retrieval = retrieval;
        this.llm = llm;
        this.subjectMapper = subjectMapper;
        this.localAnswerer = localAnswerer;
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

                send(emitter, "meta", obj(
                        "sessionId", session.getId(),
                        "subjectCode", subjectCode,
                        "model", llm.modelName(),
                        "mock", llm.isMock()));

                // ---------- 2. 用户提问落库 ----------
                ChatMsg userMsg = new ChatMsg();
                userMsg.setSessionId(session.getId());
                userMsg.setRole("user");
                userMsg.setContent(question);
                chatMapper.insertMsg(userMsg);

                // ---------- 3. 检索（限定科目）----------
                List<KbChunk> hits = retrieval.retrieve(subjectCode, question);
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
                //   · 没配 → 走离线回答引擎，只用检索到的原文组织答案，零成本、零编造
                StringBuilder answer = new StringBuilder();
                java.util.function.Consumer<String> sink = delta -> {
                    answer.append(delta);
                    send(emitter, "delta", obj("text", delta));
                };
                if (llm.isMock()) {
                    localAnswerer.answer(question, hits, sink);
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
