package com.worknote.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
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
