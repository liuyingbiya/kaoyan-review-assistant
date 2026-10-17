package com.aikb.service;

import com.aikb.mapper.KbDocMapper;
import com.aikb.mapper.SubjectMapper;
import com.aikb.model.KbDoc;
import com.aikb.model.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动时自动导入初始复习资料。
 *
 * 目录约定（子目录名 = 科目编码，必须能在 subject 表里找到）：
 *   seed/english1/xxx.md
 *   seed/math1/xxx.md
 *   seed/politics/xxx.md
 *
 * 三个设计要点：
 *  1. 【幂等】按「科目 + 标题」查重，已存在的跳过。否则每次重启都重导一遍，
 *     库里会出现几十份重复文档，检索结果全是重复片段。
 *  2. 【可关闭】rag.seed-auto=false 就不导，生产环境不会乱塞数据。
 *  3. 【不阻塞启动】导入失败只记日志不抛异常 —— 资料有问题不该让服务起不来。
 */
@Component
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private final KbDocMapper docMapper;
    private final KbService kbService;
    private final SubjectMapper subjectMapper;

    @Value("${rag.seed-auto:false}")
    private boolean seedAuto;

    @Value("${rag.seed-dir:../seed}")
    private String seedDir;

    public SeedRunner(KbDocMapper docMapper, KbService kbService, SubjectMapper subjectMapper) {
        this.docMapper = docMapper;
        this.kbService = kbService;
        this.subjectMapper = subjectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!seedAuto) {
            log.info("未开启自动导入（rag.seed-auto=false），跳过初始资料");
            return;
        }
        File root = new File(seedDir);
        if (!root.isDirectory()) {
            log.warn("种子目录不存在: {}（绝对路径 {}）", seedDir, root.getAbsolutePath());
            return;
        }

        int imported = 0;
        int skipped = 0;
        for (File dir : listDirs(root)) {
            String subjectCode = dir.getName();
            Subject subject = subjectMapper.findByCode(subjectCode);
            if (subject == null) {
                log.warn("跳过目录 {}：subject 表里没有这个科目编码", subjectCode);
                continue;
            }
            for (File f : listFiles(dir)) {
                String name = f.getName();
                String lower = name.toLowerCase();
                if (!lower.endsWith(".md") && !lower.endsWith(".txt")) {
                    continue;
                }
                String title = name.replaceAll("\\.(md|txt)$", "");
                try {
                    if (docMapper.findByTitle(subjectCode, title) != null) {
                        skipped++;
                        continue;
                    }
                    String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    KbDoc doc = kbService.importDoc(subjectCode, title, "seed/" + name, content);
                    imported++;
                    log.info("导入资料 [{}·{}] {} -> {} 个片段",
                            subject.getName(), subjectCode, title, doc.getChunkCount());
                } catch (Exception e) {
                    log.error("导入失败: {} ({})", name, e.getMessage());
                }
            }
        }
        log.info("初始资料处理完成：新导入 {} 篇，已存在跳过 {} 篇", imported, skipped);
    }

    private List<File> listDirs(File root) {
        List<File> out = new ArrayList<>();
        File[] fs = root.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f.isDirectory()) out.add(f);
        }
        return out;
    }

    private List<File> listFiles(File dir) {
        List<File> out = new ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f.isFile()) out.add(f);
        }
        return out;
    }
}
