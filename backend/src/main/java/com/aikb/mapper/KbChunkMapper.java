package com.aikb.mapper;

import com.aikb.model.KbChunk;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface KbChunkMapper {

    @Insert("<script>" +
            "INSERT INTO kb_chunk(doc_id, subject_code, seq, content) VALUES " +
            "<foreach collection='list' item='it' separator=','>" +
            "(#{it.docId}, #{it.subjectCode}, #{it.seq}, #{it.content})" +
            "</foreach>" +
            "</script>")
    int batchInsert(@Param("list") List<KbChunk> list);

    /**
     * 核心检索：MySQL 全文索引 + 相关度排序。
     *
     * 为什么不用 LIKE '%关键词%'？
     *   前置通配符用不了索引，要全表扫描；而且 LIKE 只判断"包不包含"，
     *   没有相关度，没法排序取 Top-K。
     *   MATCH AGAINST 走倒排索引，返回每条的相关度分数，可以排序。
     */
    @Select("SELECT c.id, c.doc_id, c.subject_code, c.seq, c.content, " +
            "       MATCH(c.content) AGAINST(#{q} IN NATURAL LANGUAGE MODE) AS score, " +
            "       d.title AS doc_title " +
            "FROM kb_chunk c JOIN kb_doc d ON d.id = c.doc_id " +
            "WHERE c.subject_code = #{subjectCode} " +
            "  AND MATCH(c.content) AGAINST(#{q} IN NATURAL LANGUAGE MODE) > 0 " +
            "ORDER BY score DESC LIMIT #{limit}")
    List<KbChunk> search(@Param("subjectCode") String subjectCode,
                         @Param("q") String q,
                         @Param("limit") int limit);

    /** 兜底：全文没命中时退化成模糊匹配 */
    @Select("SELECT c.id, c.doc_id, c.subject_code, c.seq, c.content, " +
            "       0 AS score, d.title AS doc_title " +
            "FROM kb_chunk c JOIN kb_doc d ON d.id = c.doc_id " +
            "WHERE c.subject_code = #{subjectCode} AND c.content LIKE CONCAT('%', #{q}, '%') " +
            "LIMIT #{limit}")
    List<KbChunk> searchLike(@Param("subjectCode") String subjectCode,
                             @Param("q") String q,
                             @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM kb_chunk WHERE subject_code = #{subjectCode}")
    int countBySubject(@Param("subjectCode") String subjectCode);
}
