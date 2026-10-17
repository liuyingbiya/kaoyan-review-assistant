package com.aikb.controller;

import com.aikb.mapper.KbChunkMapper;
import com.aikb.mapper.KbDocMapper;
import com.aikb.mapper.SubjectMapper;
import com.aikb.model.KbDoc;
import com.aikb.service.KbService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 知识库管理接口（资料按科目归属） */
@RestController
@RequestMapping("/api/kb")
public class KbController {

    private final KbService kbService;
    private final KbDocMapper docMapper;
    private final KbChunkMapper chunkMapper;
    private final SubjectMapper subjectMapper;

    public KbController(KbService kbService, KbDocMapper docMapper,
                        KbChunkMapper chunkMapper, SubjectMapper subjectMapper) {
        this.kbService = kbService;
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
        this.subjectMapper = subjectMapper;
    }

    /** 某科目的资料列表 */
    @GetMapping("/docs")
    public List<KbDoc> docs(@RequestParam String subjectCode) {
        return docMapper.listBySubject(subjectCode);
    }

    /** 全部资料（管理页用） */
    @GetMapping("/docs/all")
    public List<KbDoc> allDocs() {
        return docMapper.listAll();
    }

    /** 某科目的统计：多少篇资料、多少个片段 */
    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam String subjectCode) {
        Map<String, Object> m = new HashMap<>();
        m.put("subjectCode", subjectCode);
        m.put("docs", docMapper.countBySubject(subjectCode));
        m.put("chunks", chunkMapper.countBySubject(subjectCode));
        return m;
    }

    /** 直接粘贴文本导入 */
    @PostMapping("/import")
    public KbDoc importText(@RequestBody Map<String, String> body) {
        String subjectCode = body.get("subjectCode");
        String title = body.get("title");
        String content = body.get("content");
        if (subjectCode == null || subjectMapper.findByCode(subjectCode) == null) {
            throw new IllegalArgumentException("科目不存在: " + subjectCode);
        }
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        return kbService.importDoc(subjectCode, title.trim(), "手动录入", content);
    }

    /** 上传 .txt / .md 导入 */
    @PostMapping("/upload")
    public KbDoc upload(@RequestParam("file") MultipartFile file,
                        @RequestParam String subjectCode,
                        @RequestParam(required = false) String title) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        if (subjectMapper.findByCode(subjectCode) == null) {
            throw new IllegalArgumentException("科目不存在: " + subjectCode);
        }
        String name = file.getOriginalFilename() == null ? "未命名" : file.getOriginalFilename();
        String lower = name.toLowerCase();
        if (!lower.endsWith(".txt") && !lower.endsWith(".md")) {
            throw new IllegalArgumentException("目前只支持 .txt 和 .md 文件");
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        String docTitle = (title == null || title.trim().isEmpty())
                ? name.replaceAll("\\.(txt|md)$", "")
                : title.trim();
        return kbService.importDoc(subjectCode, docTitle, name, sb.toString());
    }

    /** 删除资料（片段靠外键级联删除） */
    @DeleteMapping("/docs/{id}")
    public Map<String, Object> delete(@PathVariable Integer id) {
        Map<String, Object> m = new HashMap<>();
        m.put("deleted", docMapper.deleteById(id));
        return m;
    }
}
