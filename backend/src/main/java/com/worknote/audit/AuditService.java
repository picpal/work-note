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

    /** 호출은 컨트롤러에서 본 작업 성공 후 수행(사후 기록) — 감사 insert 단독 실패 시 본 작업은 이미 커밋됨(의식적 트레이드오프, 폐쇄망·pool=1에서 희박). */
    public void log(UserRow user, String act, String target, String ip) {
        if (user == null) return;
        logRaw(user.emp(), act, target, ip);
    }

    public void logRaw(String who, String act, String target, String ip) {
        mapper.insert(LocalDateTime.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            who, act, target, ip);
    }
}
