package com.aikb.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 科目（树节点）。
 * 库里存成一张自关联表，这里组装成树给前端用。
 */
public class Subject {

    private String code;
    private String name;
    private String category;
    private String parentCode;
    private Integer sort;
    private String intro;

    /** 子科目（不落库，组装树时填充） */
    private List<Subject> children = new ArrayList<>();

    /** 该科目下有多少文档 / 片段（列表页显示用，不落库） */
    private Integer docCount;
    private Integer chunkCount;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getParentCode() { return parentCode; }
    public void setParentCode(String parentCode) { this.parentCode = parentCode; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public String getIntro() { return intro; }
    public void setIntro(String intro) { this.intro = intro; }
    public List<Subject> getChildren() { return children; }
    public void setChildren(List<Subject> children) { this.children = children; }
    public Integer getDocCount() { return docCount; }
    public void setDocCount(Integer docCount) { this.docCount = docCount; }
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
}
