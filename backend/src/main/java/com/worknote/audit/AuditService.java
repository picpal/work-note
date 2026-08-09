package com.worknote.audit;

import com.worknote.auth.UserRow;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 감사 로그 기록. local 모드(user=null) vault 감사는 생략, 인증 이벤트는 logRaw로 항상 기록. */
@Service
public class AuditService {

    private final AuditMapper mapper;
    private final Clock clock;

    public AuditService(AuditMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * 호출은 컨트롤러에서 본 작업 성공 후 수행(사후 기록) — 감사 insert 단독 실패 시 본 작업은 이미 커밋됨
     * (의식적 트레이드오프, 폐쇄망·pool=1에서 희박).
     *
     * <p>예외: 권한 변경 3경로(acl.set / role.update / public.set·unset)는 이 정책을 따르지 않는다.
     * 사후 재구성이 감사의 목적 자체인 경로라 서비스의 @Transactional 안에서 호출해 fail-closed로 만들었다
     * (AclAdminService·RoleAdminService 참조).
     */
    public void log(UserRow user, String act, String target, String ip) {
        log(user, act, target, ip, null);
    }

    /** detail = 변경 델타 JSON(권한 변경 경로 전용). 나머지 호출부는 4-인자 오버로드로 detail=null. */
    public void log(UserRow user, String act, String target, String ip, String detail) {
        if (user == null) return;
        logRaw(user.emp(), act, target, ip, detail);
    }

    public void logRaw(String who, String act, String target, String ip) {
        logRaw(who, act, target, ip, null);
    }

    public void logRaw(String who, String act, String target, String ip, String detail) {
        mapper.insert(LocalDateTime.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            who, act, target, ip, detail);
    }
}
