package com.worknote.auth.totp;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TotpMapper {
    void insert(TotpRow row);
    TotpRow find(@Param("userId") String userId);
    void enable(@Param("userId") String userId, @Param("at") String confirmedAt);
    void updateLastStep(@Param("userId") String userId, @Param("step") long step);
    void delete(@Param("userId") String userId);

    void insertRecovery(RecoveryRow row);
    RecoveryRow findLatestRecovery(@Param("userId") String userId);
    void markRecoveryUsed(@Param("id") String id);
    void invalidateRecovery(@Param("userId") String userId);   // 미사용 복구코드 전부 무효화
}
