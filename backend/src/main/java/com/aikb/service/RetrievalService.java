package com.aikb.service;

import com.aikb.mapper.KbChunkMapper;
import com.aikb.model.KbChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 检索服务（RAG 里的 R）。
 * 资料范围严格限定在「当前科目」内，所以英语的会话不会捞到数学的片段。
 *
 * 三级降级：
 *   1. MATCH ... AGAINST（ngram 全文索引，带相关度）—— 最准
 *   2. 摘出问题里的关键词逐个 LIKE —— 兜底
 *   3. 仍为空 → 返回空，让模型明确回答"资料里没有"
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final KbChunkMapper chunkMapper;

    @Value("${rag.top-k:5}")
    private int topK;

    public RetrievalService(KbChunkMapper chunkMapper) {
        this.chunkMapper = chunkMapper;
    }

    public int getTopK() {
        return topK;
    }

    public List<KbChunk> retrieve(String subjectCode, String question) {
        if (question == null || question.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String q = question.trim();

        List<KbChunk> hits = chunkMapper.search(subjectCode, q, topK);
        if (hits != null && !hits.isEmpty()) {
            log.debug("全文检索命中 {} 条", hits.size());
            return hits;
        }

        for (String kw : keywords(q)) {
            List<KbChunk> like = chunkMapper.searchLike(subjectCode, kw, topK);
            if (like != null && !like.isEmpty()) {
                log.debug("模糊兜底命中，关键词={}", kw);
                return like;
            }
        }
        log.debug("检索无结果，subject={}, question={}", subjectCode, q);
        return Collections.emptyList();
    }

    /** 抽关键词：去掉「什么/怎么/为什么」这类虚词，留下长度 >= 2 的片段 */
    static List<String> keywords(String question) {
        List<String> out = new ArrayList<>();
        String[] parts = question.split("[\\s，。！？、；：,.!?;:（）()\\[\\]【】\"']+");
        String[] stop = {"什么", "怎么", "为什么", "如何", "哪些", "哪个", "是不是",
                "有没有", "请问", "一下", "这个", "那个", "可以", "应该", "以及", "如果"};
        for (String p : parts) {
            p = p.trim();
            if (p.length() < 2) {
                continue;
            }
            boolean isStop = false;
            for (String s : stop) {
                if (p.equals(s)) {
                    isStop = true;
                    break;
                }
            }
            if (!isStop) {
                out.add(p);
            }
            if (out.size() >= 3) {
                break;
            }
        }
        return out;
    }
}
