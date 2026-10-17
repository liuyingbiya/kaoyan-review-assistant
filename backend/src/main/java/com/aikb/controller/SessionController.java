package com.aikb.controller;

import com.aikb.mapper.ChatMapper;
import com.aikb.model.ChatMsg;
import com.aikb.model.ChatSession;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 会话管理接口 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final ChatMapper chatMapper;

    public SessionController(ChatMapper chatMapper) {
        this.chatMapper = chatMapper;
    }

    @GetMapping
    public List<ChatSession> list(@RequestParam String subjectCode) {
        return chatMapper.listSessions(subjectCode);
    }

    /** 最近会话（跨科目），首页显示「最近在复习什么」 */
    @GetMapping("/recent")
    public List<ChatSession> recent(@RequestParam(defaultValue = "10") int limit) {
        return chatMapper.listRecent(limit);
    }

    @GetMapping("/{id}/messages")
    public List<ChatMsg> messages(@PathVariable Integer id) {
        return chatMapper.listMsgs(id);
    }

    @PutMapping("/{id}")
    public Map<String, Object> rename(@PathVariable Integer id, @RequestParam String title) {
        Map<String, Object> m = new HashMap<>();
        m.put("updated", chatMapper.renameSession(id, title));
        return m;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Integer id) {
        Map<String, Object> m = new HashMap<>();
        m.put("deleted", chatMapper.deleteSession(id));
        return m;
    }
}
