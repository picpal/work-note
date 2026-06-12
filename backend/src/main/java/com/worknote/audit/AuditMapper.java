package com.worknote.audit;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AuditMapper {
    void insert(@Param("at") String at, @Param("who") String who, @Param("act") String act,
                @Param("target") String target, @Param("ip") String ip);

    List<AuditRow> find(@Param("who") String who, @Param("act") String act,
                        @Param("from") String from, @Param("to") String to,
                        @Param("limit") int limit, @Param("offset") int offset);

    int count(@Param("who") String who, @Param("act") String act,
              @Param("from") String from, @Param("to") String to);
}
