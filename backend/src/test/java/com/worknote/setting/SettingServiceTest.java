package com.worknote.setting;

import static org.assertj.core.api.Assertions.assertThat;

import com.worknote.attachment.UploadPolicy;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
class SettingServiceTest {
    @Autowired SettingService svc;
    @Autowired JdbcTemplate jdbc;

    // 공유 in-memory DB(cache=shared) — 시드 정책을 매 테스트 전후로 복원해
    // 테스트 순서·다른 테스트 클래스의 시드 의존성을 깨지 않는다.
    private static final String SEED_EXT = "png,jpg,jpeg,gif,webp,pdf,docx,xlsx,pptx,txt,md,csv,zip";
    private static final String SEED_MAX = "26214400";

    @BeforeEach
    @AfterEach
    void restoreSeed() {
        jdbc.update("UPDATE app_setting SET value = ? WHERE key = 'upload.allowed_ext'", SEED_EXT);
        jdbc.update("UPDATE app_setting SET value = ? WHERE key = 'upload.max_bytes'", SEED_MAX);
    }

    @Test
    void seededUploadPolicy_isReadable() {
        UploadPolicy p = svc.uploadPolicy();
        assertThat(p.allowedExt()).contains("png", "pdf");
        assertThat(p.maxBytes()).isEqualTo(26214400L);
    }

    @Test
    void setUploadPolicy_persists() {
        svc.setUploadPolicy(List.of("png", "svg"), 5000);
        UploadPolicy p = svc.uploadPolicy();
        assertThat(p.allowedExt()).containsExactlyInAnyOrder("png", "svg");
        assertThat(p.maxBytes()).isEqualTo(5000L);
    }
}
