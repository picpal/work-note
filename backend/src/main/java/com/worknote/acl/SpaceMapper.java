package com.worknote.acl;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SpaceMapper {
    List<SpaceRow> findAll();
    SpaceRow find(@Param("nodeId") String nodeId);
    void upsert(@Param("nodeId") String nodeId, @Param("teamId") String teamId);
    int delete(@Param("nodeId") String nodeId);
}
