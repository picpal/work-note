package com.worknote.attachment;

import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 고아 첨부 회수 스케줄 배선. 개인 PC는 상시 기동이 아님 — 기동 60초 후 1회가 실효(TrashPurgeScheduler와 동일).
 * 테스트(60초 미만)에선 발화하지 않는다.
 *
 * <p>결과 로깅은 {@link AttachmentReapService} 안에 둔다 — 스케줄로 돌든 다른 경로로 돌든 같은 기록이 남아야 한다.
 */
@Component
public class AttachmentReapScheduler {

    private final AttachmentReapService reaper;

    public AttachmentReapScheduler(AttachmentReapService reaper) {
        this.reaper = reaper;
    }

    @Scheduled(initialDelay = 60, fixedDelay = 24 * 60 * 60, timeUnit = TimeUnit.SECONDS)
    public void run() {
        reaper.reapOrphans();
    }
}
