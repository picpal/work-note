package com.worknote.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.Scheduled;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:attreapsched?mode=memory&cache=shared",
    "worknote.upload.dir=build/test-attachment-reap-sched"
})
class AttachmentReapSchedulerTest {

    @Autowired AttachmentReapScheduler scheduler;
    @Autowired AttachmentMapper mapper;
    @Autowired Clock clock;

    /** 애노테이션 상수만 보면 run()이 빈 몸통이 돼도 통과한다 — 실제 위임까지 고정한다. */
    private static class RecordingReaper extends AttachmentReapService {
        int calls;

        RecordingReaper(AttachmentMapper m, Clock c) {
            super(m, c, "build/test-attachment-reap-sched", 24);
        }

        @Override
        public ReapResult reapOrphans() {
            calls++;
            return new ReapResult(0, 0, 0);
        }
    }

    @Test
    void run은_리퍼에_위임한다() {
        RecordingReaper reaper = new RecordingReaper(mapper, clock);

        new AttachmentReapScheduler(reaper).run();

        assertThat(reaper.calls).isEqualTo(1);
    }

    @Test
    void 기동_60초_후_1회_그리고_24시간_간격으로_배선된다() throws Exception {
        Method run = AttachmentReapScheduler.class.getMethod("run");
        Scheduled sched = run.getAnnotation(Scheduled.class);
        assertThat(sched).isNotNull();
        assertThat(sched.timeUnit()).isEqualTo(TimeUnit.SECONDS);
        assertThat(sched.initialDelay()).isEqualTo(60);   // 테스트 수명(60초 미만) 안에는 발화하지 않는다
        assertThat(sched.fixedDelay()).isEqualTo(24 * 60 * 60);
    }
}
