package com.worknote.setting;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SettingMapper {
    String get(@Param("key") String key);

    void put(@Param("key") String key, @Param("value") String value);
}
