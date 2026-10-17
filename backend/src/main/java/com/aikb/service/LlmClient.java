package com.aikb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 大模型客户端（DeepSeek，OpenAI 兼容接口）。
 *
 * 关键点：
 *  1. 用 stream=true，逐块读取 SSE 响应，而不是等整个回答生成完再返回。
 *     这样用户 0.5 秒就能看到第一个字，体感差别巨大。
 *  2. 不引入 WebClient/OkHttp，直接用 JDK 的 HttpURLConnection ——
 *     项目依赖越少，面试时越讲得清楚"这个请求到底怎么发出去的"。
 *  3. key 从配置读，支持环境变量注入，绝不硬编码。
 *  4. 没有 key 时走 mock 模式：用检索到的资料拼一段回答，
 *     保证「检索 → 拼 Prompt → 流式下发」这条链路在没有外网时也能跑通、能测。
 */
@Service
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${llm.model:deepseek-chat}")
    private String model;

    @Value("${llm.mock:false}")
    private boolean mock;

    private final ObjectMapper json = new ObjectMapper();

    public boolean isMock() {
        return mock || apiKey == null || apiKey.trim().isEmpty();
    }

    public String modelName() {
        return isMock() ? "local-mock" : model;
    }

    /** 消息：role = system / user / assistant */
    public static Map<String, String> msg(String role, String content) {
        Map<String, String> m = new HashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /**
     * 流式对话。
     * @param messages   完整消息列表（含 system 与历史）
     * @param onDelta    每收到一个片段就回调一次
     * @param fallback   检索到的资料，仅 mock 模式使用
     */
    public void streamChat(List<Map<String, String>> messages,
                           Consumer<String> onDelta,
                           List<String> fallback) throws Exception {

        if (isMock()) {
            mockStream(onDelta, fallback);
            return;
        }

        URL url = new URL(baseUrl + "/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(180000);

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("stream", true);
        payload.put("temperature", 0.3);   // 问答类任务要稳，不要发散

        byte[] body = json.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            StringBuilder err = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    err.append(line);
                }
            } catch (Exception ignore) {
                // 读不到错误体就用状态码兜底
            }
            throw new RuntimeException("大模型接口返回 " + code + "：" +
                    (err.length() > 300 ? err.substring(0, 300) : err.toString()));
        }

        // 逐行解析 SSE：形如  data: {"choices":[{"delta":{"content":"你"}}]}
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }
                try {
                    JsonNode node = json.readTree(data);
                    JsonNode delta = node.path("choices").path(0).path("delta").path("content");
                    if (!delta.isMissingNode() && !delta.isNull()) {
                        String text = delta.asText("");
                        if (!text.isEmpty()) {
                            onDelta.accept(text);
                        }
                    }
                } catch (Exception parseErr) {
                    log.warn("跳过无法解析的 SSE 片段: {}", data);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 本地模拟模式：没有 key 时的降级方案。
     * 从检索到的资料里挑出和问题最贴近的句子拼成回答，
     * 目的是让「检索是否命中、流式是否正确、引用是否显示」这些工程问题可以先验证。
     */
    private void mockStream(Consumer<String> onDelta, List<String> fallback) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("（本地模拟模式：未配置 DEEPSEEK_API_KEY，以下内容由检索结果拼接，非大模型生成）\n\n");
        if (fallback == null || fallback.isEmpty()) {
            sb.append("知识库里没有检索到相关内容。请先导入文档，或换一个问法。");
        } else {
            sb.append("根据知识库检索到的资料：\n\n");
            int i = 1;
            for (String ctx : fallback) {
                String brief = ctx.length() > 180 ? ctx.substring(0, 180) + "…" : ctx;
                sb.append(i++).append(". ").append(brief).append("\n");
            }
            sb.append("\n配置 API Key 后可获得大模型整合后的回答。");
        }
        // 按字符流式下发，模拟真实打字效果
        String text = sb.toString();
        for (int i = 0; i < text.length(); i += 3) {
            int end = Math.min(i + 3, text.length());
            onDelta.accept(text.substring(i, end));
            Thread.sleep(12);
        }
    }
}
