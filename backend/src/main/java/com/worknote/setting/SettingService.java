package com.worknote.setting;

import com.worknote.attachment.UploadPolicy;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** app_setting 기반 런타임 설정. 업로드 정책 + 2FA 유예 일수 + Redmine 연동. */
@Service
public class SettingService {
    static final String KEY_EXT = "upload.allowed_ext";
    static final String KEY_MAX = "upload.max_bytes";
    static final String KEY_GRACE_DAYS = "2fa.grace_days";
    static final String KEY_REDMINE_ENABLED = "redmine.enabled";
    static final String KEY_REDMINE_BASE_URL = "redmine.base_url";
    private static final long DEFAULT_MAX = 26214400L; // seed 누락 시 안전망
    private static final int DEFAULT_GRACE_DAYS = 7;   // seed 누락/손상 시 안전망

    private final SettingMapper mapper;

    public SettingService(SettingMapper mapper) {
        this.mapper = mapper;
    }

    /** admin 2FA 강제 유예 일수 — 값이 null이거나 숫자가 아니면 기본 7. */
    @Transactional(readOnly = true)
    public int graceDays() {
        String v = mapper.get(KEY_GRACE_DAYS);
        if (v == null || v.isBlank()) {
            return DEFAULT_GRACE_DAYS;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_GRACE_DAYS;
        }
    }

    @Transactional(readOnly = true)
    public UploadPolicy uploadPolicy() {
        String exts = mapper.get(KEY_EXT);
        String max = mapper.get(KEY_MAX);
        List<String> list = (exts == null || exts.isBlank()) ? List.of() : Arrays.asList(exts.split(","));
        long maxBytes = (max == null || max.isBlank()) ? DEFAULT_MAX : Long.parseLong(max.trim());
        return UploadPolicy.of(list, maxBytes);
    }

    // ─── Redmine 연동 설정 ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public boolean redmineEnabled() {
        return "1".equals(mapper.get(KEY_REDMINE_ENABLED));
    }

    @Transactional(readOnly = true)
    public String redmineBaseUrl() {
        String v = mapper.get(KEY_REDMINE_BASE_URL);
        return v == null ? "" : v.trim();
    }

    @Transactional
    public void setRedmine(boolean enabled, String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        if (!url.isEmpty()) {
            com.worknote.redmine.RedmineUrlValidator.validateForSave(url);   // SSRF 가드 (감사 §2-2)
        }
        mapper.put(KEY_REDMINE_ENABLED, enabled ? "1" : "0");
        mapper.put(KEY_REDMINE_BASE_URL, url);
    }

    // ─── 업로드 정책 ─────────────────────────────────────────────────────

    /**
     * 형식을 어긴 값은 저장하지 않고 사유가 담긴 422로 되돌린다.
     * 예전에는 "bad!" 같은 값이 그대로 들어가, 관리자는 허용했다고 믿는데 해당 업로드는
     * 계속 거부되는 조용한 오설정이 됐다(어떤 파일 확장자와도 매치되지 않으므로).
     */
    @Transactional
    public void setUploadPolicy(List<String> exts, long maxBytes) {
        UploadPolicy.validateMaxBytes(maxBytes);
        String joined = String.join(",", UploadPolicy.validateExts(exts));
        mapper.put(KEY_EXT, joined);
        mapper.put(KEY_MAX, String.valueOf(maxBytes));
    }
}
