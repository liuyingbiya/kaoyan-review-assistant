package com.aikb.model;

/** 文档切片：检索的最小单位 */
public class KbChunk {
    private Integer id;
    private Integer docId;
    private String subjectCode;
    private Integer seq;
    private String content;
    /** 全文检索相关度（MATCH ... AGAINST 返回，不落库） */
    private Double score;
    /** 所属文档标题，用于前端显示引用来源 */
    private String docTitle;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getDocId() { return docId; }
    public void setDocId(Integer docId) { this.docId = docId; }
    public String getSubjectCode() { return subjectCode; }
    public void setSubjectCode(String subjectCode) { this.subjectCode = subjectCode; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public String getDocTitle() { return docTitle; }
    public void setDocTitle(String docTitle) { this.docTitle = docTitle; }
}
