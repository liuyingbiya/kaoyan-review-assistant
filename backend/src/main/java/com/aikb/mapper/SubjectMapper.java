package com.aikb.mapper;

import com.aikb.model.Subject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SubjectMapper {

    @Select("SELECT * FROM subject ORDER BY sort ASC")
    List<Subject> listAll();

    @Select("SELECT * FROM subject WHERE code = #{code}")
    Subject findByCode(@Param("code") String code);

    /** 该科目下的文档数（前端科目卡片上显示） */
    @Select("SELECT COUNT(*) FROM kb_doc WHERE subject_code = #{code}")
    int countDocs(@Param("code") String code);

    @Select("SELECT COUNT(*) FROM kb_chunk WHERE subject_code = #{code}")
    int countChunks(@Param("code") String code);
}
