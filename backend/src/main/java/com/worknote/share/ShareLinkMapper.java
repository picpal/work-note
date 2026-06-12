package com.worknote.share;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface ShareLinkMapper {
    void insert(ShareLinkRow row);
    ShareLinkRow findById(@Param("id") String id);
    ShareLinkRow findByToken(@Param("token") String token);
    List<ShareLinkRow> findActiveByNode(@Param("nodeId") String nodeId, @Param("now") String now);
    List<ActiveShareRow> findAllActive(@Param("now") String now);   // 휴지통 노드 링크 포함(suspend 표시 — 결정 S14)
    void incrementViewCount(@Param("id") String id);
    void revoke(@Param("id") String id, @Param("revokedAt") String revokedAt);
    void deleteIn(@Param("nodeIds") List<String> nodeIds);          // purge 종속행 삭제 (결정 S4)
}
