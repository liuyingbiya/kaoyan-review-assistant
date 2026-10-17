package com.aikb.model;

import java.time.LocalDateTime;

/** 对话消息 */
public class ChatMsg {
    private Integer id;
    private Integer sessionId;
    /** user / assistant */
    private String role;
    private String content;
    /** 引用片段，JSON 字符串 */
    private String citations;
    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getSessionId() { return sessionId; }
    public void setSessionId(Integer sessionId) { this.sessionId = sessionId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getCitations() { return citations; }
    public void setCitations(String citations) { this.citations = citations; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
