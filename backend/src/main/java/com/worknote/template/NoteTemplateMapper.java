package com.worknote.template;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface NoteTemplateMapper {
    /** 시스템 템플릿 + ownerId 소유분. 시스템 우선, 각 그룹 내 이름 오름차순. */
    List<NoteTemplateRow> listVisible(@Param("ownerId") String ownerId);

    List<NoteTemplateRow> listSystem();

    NoteTemplateRow find(@Param("id") String id);

    void insert(NoteTemplateRow row);

    void update(NoteTemplateRow row);

    void delete(@Param("id") String id);

    int countByOwner(@Param("ownerId") String ownerId);
}
