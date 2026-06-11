package com.worknote.audit;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuditMapper {
    void insert(@Param("at") String at, @Param("who") String who, @Param("act") String act,
                @Param("target") String target, @Param("ip") String ip);
}
