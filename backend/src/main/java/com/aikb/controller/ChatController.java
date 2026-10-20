package com.aikb.controller;

import com.aikb.service.ChatService;
import com.aikb.service.LlmClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

/** 对话接口（流式） */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final LlmClient llm;

    public ChatController(ChatService chatService, LlmClient llm) {
        this.chatService = chatService;
        this.llm = llm;
    }

    /**
     * 流式问答。
     * 前端用 EventSource 订阅（浏览器原生、自动重连），所以用 GET + query 参数。
     *
     * SSE 事件（每行一个 JSON，靠 type 区分）：
     *   meta      会话已建立：sessionId / subjectCode / model / mock
     *   delta     模型输出的一段文字
     *   citations 引用片段列表
     *   done      结束
     *   error     出错
     */
    @GetMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter stream(@RequestParam(required = false) Integer sessionId,
                             @RequestParam(defaultValue = "math1") String subjectCode,
                             @RequestParam String question) {
        return chatService.ask(sessionId, subjectCode, question);
    }

    /** 模型状态：前端据此提示「当前是否接了真实模型」 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new HashMap<>();
        m.put("mock", llm.isMock());
        m.put("model", llm.modelName());
        m.put("hint", llm.isMock()
                ? "离线检索模式（不调用大模型、不消耗额度）"
                : "已接入 DeepSeek");
        // 使用范围声明：前端在开始界面直接展示，避免用户问无关问题
        m.put("notice", ChatService.SCOPE_NOTICE);
        return m;
    }
}
