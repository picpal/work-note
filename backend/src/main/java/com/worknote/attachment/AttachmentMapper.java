package com.worknote.attachment;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AttachmentMapper {
    void insert(AttachmentRow row);

    AttachmentRow findById(@Param("id") String id);

    List<AttachmentRow> findByNode(@Param("nodeId") String nodeId);

    void delete(@Param("id") String id);

    List<AttachmentRow> findByNodeIds(@Param("ids") List<String> ids);

    void deleteByNodeIds(@Param("ids") List<String> ids);
}
