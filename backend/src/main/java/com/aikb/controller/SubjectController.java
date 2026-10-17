package com.aikb.controller;

import com.aikb.mapper.SubjectMapper;
import com.aikb.model.Subject;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 科目接口。
 *
 * 前端（你负责的 Vue 部分）用它来渲染左侧科目树：
 *   GET /api/subjects        → 两级树（分组 → 科目）
 *   GET /api/subjects/flat   → 扁平列表（做下拉框时更方便）
 */
@RestController
@RequestMapping("/api/subjects")
public class SubjectController {

    private final SubjectMapper subjectMapper;

    public SubjectController(SubjectMapper subjectMapper) {
        this.subjectMapper = subjectMapper;
    }

    /**
     * 两级树。
     * 组装时顺便把每个科目的文档数 / 片段数填上，前端不用再逐个查。
     */
    @GetMapping
    public List<Subject> tree() {
        List<Subject> all = subjectMapper.listAll();
        for (Subject s : all) {
            s.setDocCount(subjectMapper.countDocs(s.getCode()));
            s.setChunkCount(subjectMapper.countChunks(s.getCode()));
        }

        Map<String, Subject> byCode = new LinkedHashMap<>();
        for (Subject s : all) {
            byCode.put(s.getCode(), s);
        }

        List<Subject> roots = new ArrayList<>();
        for (Subject s : all) {
            if (s.getParentCode() == null || s.getParentCode().isEmpty()) {
                roots.add(s);
            } else {
                Subject parent = byCode.get(s.getParentCode());
                if (parent != null) {
                    parent.getChildren().add(s);
                }
            }
        }
        return roots;
    }

    /** 扁平列表：只返回真正能提问的科目（有文档的叶子节点优先） */
    @GetMapping("/flat")
    public List<Map<String, Object>> flat() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Subject s : subjectMapper.listAll()) {
            Map<String, Object> m = new HashMap<>();
            m.put("code", s.getCode());
            m.put("name", s.getName());
            m.put("category", s.getCategory());
            m.put("parentCode", s.getParentCode());
            m.put("docCount", subjectMapper.countDocs(s.getCode()));
            out.add(m);
        }
        return out;
    }
}
