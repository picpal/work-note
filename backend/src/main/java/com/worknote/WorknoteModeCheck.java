package com.worknote;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * worknote.mode 프로퍼티 기동 시 검증 — 오타(fail-open) 방지.
 * 컨트롤러/필터/권한 서비스가 같은 스위치를 각자 파싱하므로 여기서 한 번만 검증한다.
 */
@Component
public class WorknoteModeCheck {

    public WorknoteModeCheck(@Value("${worknote.mode:local}") String mode) {
        if (!"local".equals(mode) && !"server".equals(mode)) {
            throw new IllegalStateException("worknote.mode는 local 또는 server여야 합니다: " + mode);
        }
    }
}
