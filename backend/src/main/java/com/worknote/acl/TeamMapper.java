package com.worknote.acl;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface TeamMapper {
    void insertTeam(@Param("id") String id, @Param("name") String name);
    void addMember(@Param("teamId") String teamId, @Param("userId") String userId);
    List<String> teamsOf(@Param("userId") String userId);
    List<TeamRow> findAll();
    TeamRow findById(@Param("id") String id);
    List<com.worknote.auth.UserRow> membersOf(@Param("teamId") String teamId);
    void updateTeam(@Param("id") String id, @Param("name") String name);
    void deleteTeam(@Param("id") String id);
    int removeMember(@Param("teamId") String teamId, @Param("userId") String userId);
    void deleteMembers(@Param("teamId") String teamId);
    int isMember(@Param("teamId") String teamId, @Param("userId") String userId);
    int countSpaces(@Param("teamId") String teamId);
}
