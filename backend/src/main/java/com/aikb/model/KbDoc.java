package com.aikb.model;

import java.time.LocalDateTime;

/** 知识库文档 */
public class KbDoc {
    private Integer id;
    /** 所属科目编码，如 english1 / math1 / politics */
    private String subjectCode;
    /** 冗余一份科目名，前端展示用（查询时 join 出来，不落库） */
    private String subjectName;
    private String title;
    private String source;
    private Integer charCount;
    private LocalDateTime createdAt;
    private Integer chunkCount;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getSubjectCode() { return subjectCode; }
    public void setSubjectCode(String subjectCode) { this.subjectCode = subjectCode; }
    public String getSubjectName() { return subjectName; }
    public void setSubjectName(String subjectName) { this.subjectName = subjectName; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Integer getCharCount() { return charCount; }
    public void setCharCount(Integer charCount) { this.charCount = charCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
}
