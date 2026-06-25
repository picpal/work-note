package com.worknote.redmine;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RedmineTokenMapper {
    void upsert(RedmineTokenRow row);
    RedmineTokenRow find(@Param("userId") String userId);
    void delete(@Param("userId") String userId);
}
