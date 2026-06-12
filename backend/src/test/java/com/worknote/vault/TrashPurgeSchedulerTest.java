package com.worknote.vault;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
class TrashPurgeSchedulerTest {

    @Autowired TrashPurgeScheduler scheduler;

    @Test
    void 기동_60초_후_1회_그리고_24시간_간격으로_배선된다() throws Exception {
        Method run = TrashPurgeScheduler.class.getMethod("run");
        Scheduled sched = run.getAnnotation(Scheduled.class);
        assertThat(sched).isNotNull();
        assertThat(sched.timeUnit()).isEqualTo(TimeUnit.SECONDS);
        assertThat(sched.initialDelay()).isEqualTo(60);
        assertThat(sched.fixedDelay()).isEqualTo(24 * 60 * 60);
    }
}
