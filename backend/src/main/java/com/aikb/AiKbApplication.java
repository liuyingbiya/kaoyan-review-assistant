package com.aikb;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI 知识库问答 · 后端入口
 *
 * 架构：
 *   Controller（接口层）
 *     └─ Service（业务层：知识库导入 / 检索 / 对话编排）
 *          ├─ Mapper（MyBatis，MySQL）
 *          └─ LlmClient（调用 DeepSeek，OpenAI 兼容接口，流式）
 */
@SpringBootApplication
@MapperScan("com.aikb.mapper")
public class AiKbApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiKbApplication.class, args);
    }
}
