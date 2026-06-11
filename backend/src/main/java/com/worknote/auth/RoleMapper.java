package com.worknote.auth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RoleMapper {
    RoleRow findById(@Param("id") String id);
    List<RoleRow> findAll();
    void insert(RoleRow row);
    void update(RoleRow row);
    void delete(@Param("id") String id);
    int countUsers(@Param("roleId") String roleId);
}
