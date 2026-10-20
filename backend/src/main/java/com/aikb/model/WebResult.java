package com.aikb.model;

/**
 * 一条联网搜索结果。
 *
 * 为什么不复用 KbChunk：KbChunk 是「资料库里的切片」，有 docId / seq / 相关度分数；
 * 网页结果只有标题、链接、摘要，语义不同。混用一个类型会让引用来源很难区分。
 */
public class WebResult {
    private String title;
    private String url;
    private String snippet;

    public WebResult() {
    }

    public WebResult(String title, String url, String snippet) {
        this.title = title;
        this.url = url;
        this.snippet = snippet;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }
}
