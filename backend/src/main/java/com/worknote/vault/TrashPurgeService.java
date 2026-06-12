package com.worknote.vault;

import com.worknote.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 휴지통 30일 자동 purge (스펙 §4.3). 스케줄 배선은 TrashPurgeScheduler — 로직 분리로 테스트 직접 호출. */
@Service
public class TrashPurgeService {

    private static final Logger log = LoggerFactory.getLogger(TrashPurgeService.class);

    private final VaultService vault;
    private final NodeMapper nodes;
    private final AuditService audit;
    private final Clock clock;
    private final int retentionDays;

    public TrashPurgeService(VaultService vault, NodeMapper nodes, AuditService audit, Clock clock,
                             @Value("${worknote.purge.retention-days:30}") int retentionDays) {
        this.vault = vault;
        this.nodes = nodes;
        this.audit = audit;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    /** @return purge된 휴지통 루트 수. retention-days 0 이하 = 비활성. */
    public int purgeExpired() {
        if (retentionDays <= 0) return 0;
        String cutoff = LocalDateTime.now(clock).minusDays(retentionDays)
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        int purged = 0;
        for (NodeRow root : nodes.findExpiredTrashRoots(cutoff)) {
            try {
                vault.purge(root.id());
            } catch (Exception e) {
                // 한 루트 실패가 나머지를 막지 않음 (확정 결정 P5)
                log.warn("자동 purge 실패: {}", root.id(), e);
                continue;
            }
            purged++;
            try {
                // 자동/수동 구분은 who — act는 node.purge 재사용 (확정 결정 P4)
                audit.logRaw("system", "node.purge",
                    root.id() + " (보존기한 " + retentionDays + "일 경과)", null);
            } catch (Exception e) {
                // purge는 이미 완료 — 감사 단독 실패는 AuditService의 문서화된 트레이드오프와 동일
                log.warn("자동 purge 감사 기록 실패: {}", root.id(), e);
            }
        }
        return purged;
    }
}
