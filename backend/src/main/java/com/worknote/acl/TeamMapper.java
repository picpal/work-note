package com.worknote.acl;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface TeamMapper {
    void insertTeam(@Param("id") String id, @Param("name") String name);
    void addMember(@Param("teamId") String teamId, @Param("userId") String userId);
    List<String> teamsOf(@Param("userId") String userId);
}
