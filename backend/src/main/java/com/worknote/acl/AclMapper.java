package com.worknote.acl;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AclMapper {
    /** 재귀 CTE — 존재하는 노드면 자기 자신 포함 ≥1, 자신→루트 순(depth asc). IN-리스트 비지 않음 계약. */
    List<String> ancestorChain(@Param("id") String id);
    List<AclRow> findAclForNodes(@Param("nodeIds") List<String> nodeIds);
    List<AclRow> findAllAcl();
    List<PublicFlagRow> findPublicFlagsForNodes(@Param("nodeIds") List<String> nodeIds);
    List<PublicFlagRow> findAllPublicFlags();
    void insertAcl(AclRow row);
    void insertPublicFlag(@Param("nodeId") String nodeId, @Param("mode") String mode);

    // purge 종속행 정리 — NodeMapper.deleteTagsIn과 동일 패턴 (IN-리스트 비지 않음 계약)
    void deleteAclIn(@Param("ids") List<String> ids);
    void deletePublicFlagIn(@Param("ids") List<String> ids);
    void deleteSpaceIn(@Param("ids") List<String> ids);
}
