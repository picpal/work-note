package com.worknote.setting;

import com.worknote.attachment.UploadPolicy;
import com.worknote.vault.VaultException;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** app_setting 기반 런타임 설정. 현재는 업로드 정책만. */
@Service
public class SettingService {
    static final String KEY_EXT = "upload.allowed_ext";
    static final String KEY_MAX = "upload.max_bytes";
    private static final long DEFAULT_MAX = 26214400L; // seed 누락 시 안전망

    private final SettingMapper mapper;

    public SettingService(SettingMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public UploadPolicy uploadPolicy() {
        String exts = mapper.get(KEY_EXT);
        String max = mapper.get(KEY_MAX);
        List<String> list = (exts == null || exts.isBlank()) ? List.of() : Arrays.asList(exts.split(","));
        long maxBytes = (max == null || max.isBlank()) ? DEFAULT_MAX : Long.parseLong(max.trim());
        return UploadPolicy.of(list, maxBytes);
    }

    @Transactional
    public void setUploadPolicy(List<String> exts, long maxBytes) {
        if (maxBytes < 1) {
            throw VaultException.invalid("최대 용량은 1 이상이어야 합니다");
        }
        // UploadPolicy.of로 정규화(소문자·점 제거·중복 제거) 후 저장
        String joined = String.join(",", UploadPolicy.of(exts, maxBytes).allowedExt());
        mapper.put(KEY_EXT, joined);
        mapper.put(KEY_MAX, String.valueOf(maxBytes));
    }
}
