package com.aikb.service;

import com.aikb.mapper.KbChunkMapper;
import com.aikb.mapper.KbDocMapper;
import com.aikb.model.KbChunk;
import com.aikb.model.KbDoc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库导入：把整篇资料切成片段入库。
 *
 * 切分参数：
 *   chunk-size    每片最多多少字。太小上下文不完整，太大检索不精准、浪费 token。
 *   chunk-overlap 相邻片段重叠多少字。防止一句话正好被切断，两边都不完整。
 */
@Service
public class KbService {

    private final KbDocMapper docMapper;
    private final KbChunkMapper chunkMapper;

    @Value("${rag.chunk-size:400}")
    private int chunkSize;

    @Value("${rag.chunk-overlap:80}")
    private int chunkOverlap;

    public KbService(KbDocMapper docMapper, KbChunkMapper chunkMapper) {
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
    }

    /**
     * 导入一篇资料。
     * 整个方法在一个事务里：文档表和切片表要么都成功、要么都回滚，
     * 否则会出现「文档存在但没有任何片段」的脏数据，检索永远查不到它。
     */
    @Transactional(rollbackFor = Exception.class)
    public KbDoc importDoc(String subjectCode, String title, String source, String content) {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("文档内容为空");
        }

        KbDoc doc = new KbDoc();
        doc.setSubjectCode(subjectCode);
        doc.setTitle(title);
        doc.setSource(source);
        doc.setCharCount(content.length());
        docMapper.insert(doc);

        List<String> pieces = split(content);
        List<KbChunk> chunks = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            KbChunk c = new KbChunk();
            c.setDocId(doc.getId());
            c.setSubjectCode(subjectCode);
            c.setSeq(i + 1);
            c.setContent(pieces.get(i));
            chunks.add(c);
        }
        int batch = 100;
        for (int i = 0; i < chunks.size(); i += batch) {
            chunkMapper.batchInsert(chunks.subList(i, Math.min(i + batch, chunks.size())));
        }
        doc.setChunkCount(chunks.size());
        return doc;
    }

    /**
     * 按「句子边界优先」切分：
     * 先按 。！？；换行 切成句子，再往片段里累加，超过 chunkSize 就断开，
     * 并保留最后 overlap 个字作为下一片的开头。
     * 这样不会把一句话从中间劈开，检索出来的片段读起来是完整的。
     */
    public List<String> split(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            cur.append(ch);
            boolean end = (ch == '。' || ch == '！' || ch == '？' || ch == '；'
                    || ch == '\n' || ch == '.' && i + 1 < text.length() && text.charAt(i + 1) == ' ');
            if (end || cur.length() >= chunkSize) {
                // 只去掉行尾空格，保留换行 —— 否则「# 标题## 小节」会连成一串，
                // 模型看不出结构，片段读起来也难受
                String s = cur.toString().replaceAll("[ \t]+$", "");
                if (!s.trim().isEmpty()) {
                    sentences.add(s);
                }
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            String s = cur.toString().replaceAll("[ \t]+$", "");
            if (!s.trim().isEmpty()) {
                sentences.add(s);
            }
        }

        List<String> pieces = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        for (String s : sentences) {
            if (chunk.length() + s.length() > chunkSize && chunk.length() > 0) {
                String done = chunk.toString();
                pieces.add(done);
                int keep = Math.min(chunkOverlap, done.length() / 2);
                chunk.setLength(0);
                chunk.append(done.substring(done.length() - keep));
            }
            chunk.append(s);
        }
        if (chunk.length() > 0) {
            pieces.add(chunk.toString());
        }
        return pieces;
    }
}
