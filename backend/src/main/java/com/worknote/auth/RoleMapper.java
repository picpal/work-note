package com.worknote.auth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RoleMapper {
    RoleRow findById(@Param("id") String id);
}
