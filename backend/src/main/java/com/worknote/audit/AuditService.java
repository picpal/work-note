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

    public void log(UserRow user, String act, String target, String ip) {
        if (user == null) return;
        logRaw(user.emp(), act, target, ip);
    }

    public void logRaw(String who, String act, String target, String ip) {
        mapper.insert(LocalDateTime.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            who, act, target, ip);
    }
}
