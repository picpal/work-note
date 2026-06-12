package com.worknote.vault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** purge 스케줄 배선. 개인 PC는 상시 기동이 아님 — 기동 60초 후 1회가 실효(확정 결정 P1). 테스트(60초 미만)에선 발화 안 함. */
@Component
public class TrashPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrashPurgeScheduler.class);

    private final TrashPurgeService purge;

    public TrashPurgeScheduler(TrashPurgeService purge) {
        this.purge = purge;
    }

    @Scheduled(initialDelay = 60, fixedDelay = 24 * 60 * 60, timeUnit = TimeUnit.SECONDS)
    public void run() {
        int n = purge.purgeExpired();
        if (n > 0) {
            log.info("휴지통 자동 purge: {}건", n);
        }
    }
}
