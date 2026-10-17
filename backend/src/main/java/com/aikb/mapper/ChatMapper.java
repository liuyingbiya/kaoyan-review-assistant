package com.aikb.mapper;

import com.aikb.model.ChatMsg;
import com.aikb.model.ChatSession;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ChatMapper {

    // ---------------- 会话 ----------------
    @Insert("INSERT INTO chat_session(subject_code, title) VALUES(#{subjectCode}, #{title})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSession(ChatSession session);

    @Select("SELECT s.*, j.name AS subject_name FROM chat_session s " +
            "LEFT JOIN subject j ON j.code = s.subject_code " +
            "WHERE s.subject_code = #{subjectCode} ORDER BY s.id DESC")
    List<ChatSession> listSessions(@Param("subjectCode") String subjectCode);

    @Select("SELECT s.*, j.name AS subject_name FROM chat_session s " +
            "LEFT JOIN subject j ON j.code = s.subject_code WHERE s.id = #{id}")
    ChatSession findSession(@Param("id") Integer id);

    @Update("UPDATE chat_session SET title = #{title} WHERE id = #{id}")
    int renameSession(@Param("id") Integer id, @Param("title") String title);

    /** 最近会话（跨科目），首页显示「最近在复习什么」 */
    @Select("SELECT s.*, j.name AS subject_name FROM chat_session s " +
            "LEFT JOIN subject j ON j.code = s.subject_code " +
            "ORDER BY s.id DESC LIMIT #{limit}")
    List<ChatSession> listRecent(@Param("limit") int limit);

    @Delete("DELETE FROM chat_session WHERE id = #{id}")
    int deleteSession(@Param("id") Integer id);

    // ---------------- 消息 ----------------
    @Insert("INSERT INTO chat_msg(session_id, role, content, citations) " +
            "VALUES(#{sessionId}, #{role}, #{content}, #{citations})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertMsg(ChatMsg msg);

    @Select("SELECT * FROM chat_msg WHERE session_id = #{sessionId} ORDER BY id ASC")
    List<ChatMsg> listMsgs(@Param("sessionId") Integer sessionId);
}
