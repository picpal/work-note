package com.worknote.vault;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface NodeMapper {
    void insert(NodeRow row);
    NodeRow findById(@Param("id") String id);
    List<NodeRow> findActive();                                   // deleted_at IS NULL 전체 (트리 조립용)
    List<String> subtreeIds(@Param("id") String id);              // 재귀 CTE — 자신 포함 자손 id
    int maxPosition(@Param("parentId") String parentId);          // 형제 최대 position (없으면 0)
    void updateFields(@Param("id") String id, @Param("name") String name,
                      @Param("content") String content, @Param("updatedAt") String updatedAt);
    void move(@Param("id") String id, @Param("parentId") String parentId, @Param("position") int position);
    void softDeleteSubtree(@Param("id") String id, @Param("deletedAt") String deletedAt, @Param("deletedBy") String deletedBy);
    void restoreSubtree(@Param("id") String id);
    void purgeSubtree(@Param("id") String id);
    List<NodeRow> findTrashRoots();                               // 삭제됐지만 부모는 비삭제(또는 무부모)인 루트만
    List<String> findTags(@Param("nodeId") String nodeId);
    void deleteTags(@Param("nodeId") String nodeId);
    void insertTag(@Param("nodeId") String nodeId, @Param("tag") String tag);
    void deleteTagsIn(@Param("ids") List<String> ids);
    default void replaceTags(String nodeId, List<String> tags) {
        deleteTags(nodeId);
        for (String t : tags) insertTag(nodeId, t);
    }
}
