package com.aikb.mapper;

import com.aikb.model.KbDoc;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface KbDocMapper {

    @Insert("INSERT INTO kb_doc(subject_code, title, source, char_count) " +
            "VALUES(#{subjectCode}, #{title}, #{source}, #{charCount})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KbDoc doc);

    /**
     * 按科目查文档，顺带 join 出科目名和片段数。
     * 一次查询把前端要展示的字段都带回来，避免对每行再查一次（N+1）。
     */
    @Select("SELECT d.*, s.name AS subject_name, COUNT(c.id) AS chunk_count " +
            "FROM kb_doc d " +
            "LEFT JOIN kb_chunk c ON c.doc_id = d.id " +
            "LEFT JOIN subject s ON s.code = d.subject_code " +
            "WHERE d.subject_code = #{subjectCode} " +
            "GROUP BY d.id, s.name ORDER BY d.id DESC")
    List<KbDoc> listBySubject(@Param("subjectCode") String subjectCode);

    /** 全部文档（管理页用） */
    @Select("SELECT d.*, s.name AS subject_name, COUNT(c.id) AS chunk_count " +
            "FROM kb_doc d " +
            "LEFT JOIN kb_chunk c ON c.doc_id = d.id " +
            "LEFT JOIN subject s ON s.code = d.subject_code " +
            "GROUP BY d.id, s.name ORDER BY d.id DESC")
    List<KbDoc> listAll();

    @Select("SELECT * FROM kb_doc WHERE id = #{id}")
    KbDoc findById(@Param("id") Integer id);

    /** 按标题查重：自动导入时判断是否导过（幂等） */
    @Select("SELECT * FROM kb_doc WHERE subject_code = #{subjectCode} AND title = #{title} LIMIT 1")
    KbDoc findByTitle(@Param("subjectCode") String subjectCode, @Param("title") String title);

    @Delete("DELETE FROM kb_doc WHERE id = #{id}")
    int deleteById(@Param("id") Integer id);

    @Select("SELECT COUNT(*) FROM kb_doc WHERE subject_code = #{subjectCode}")
    int countBySubject(@Param("subjectCode") String subjectCode);
}
