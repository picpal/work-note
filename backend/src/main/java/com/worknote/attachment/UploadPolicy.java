package com.worknote.attachment;

import com.worknote.vault.VaultException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 업로드 허용 정책 — 확장자 allowlist + 파일당 최대 바이트. 순수 검사 로직(테스트 용이). */
public final class UploadPolicy {
    private static final Set<String> IMAGE_EXTS = Set.of("png", "jpg", "jpeg", "gif", "webp");

    /**
     * 정책에 저장할 수 있는 확장자 표기 — 영문 소문자·숫자 1~16자.
     * ext(filename)이 뽑아내는 값과 같은 모양이어야 한다. ".BAD!" 같은 값은 저장돼도
     * 어떤 파일과도 매치되지 않아 "허용했는데 계속 거부되는" 조용한 오설정이 된다.
     */
    public static final Pattern EXT_PATTERN = Pattern.compile("[a-z0-9]{1,16}");

    /**
     * 정책으로 지정 가능한 파일당 최대 바이트의 상한 = application.yml의 multipart
     * max-file-size(64MB)와 같은 값. 그 위는 서블릿이 먼저 막으므로 정책 숫자만 커지는
     * 오설정이고, 25를 2500으로 잘못 친 오타가 디스크를 그대로 열어준다.
     */
    public static final long MAX_BYTES_LIMIT = 64L * 1024 * 1024;

    private final Set<String> allowedExt;
    private final long maxBytes;

    private UploadPolicy(Set<String> allowedExt, long maxBytes) {
        this.allowedExt = allowedExt;
        this.maxBytes = maxBytes;
    }

    /** 읽기 경로 — 관대하게 정규화만 한다(과거에 저장된 값 때문에 앱이 못 뜨면 안 된다). */
    public static UploadPolicy of(List<String> exts, long maxBytes) {
        Set<String> set = new LinkedHashSet<>();
        for (String e : exts) {
            String n = normalizeExt(e);
            if (!n.isEmpty()) {
                set.add(n);
            }
        }
        return new UploadPolicy(set, maxBytes);
    }

    /** 입력 표기 정리 — 앞뒤 공백 제거, 소문자화, 맨 앞 점 1개 제거. 검증은 하지 않는다. */
    public static String normalizeExt(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase().replaceFirst("^\\.", "");
    }

    /**
     * 저장 경로 검증 — 정규화 후 형식을 어기면 사유가 담긴 422. 통과하면 정규화·중복 제거된 목록을 돌려준다.
     * 화면(프런트)에도 같은 규칙이 있지만 API 직접 호출 경로가 있어 여기서도 막는다.
     */
    public static List<String> validateExts(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw VaultException.invalid("허용 확장자를 1개 이상 지정하세요");
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String e : raw) {
            String n = normalizeExt(e);
            if (!EXT_PATTERN.matcher(n).matches()) {
                throw VaultException.invalid(
                    "허용 확장자 형식이 올바르지 않습니다: \"" + (e == null ? "" : e.trim())
                        + "\" — 점(.) 없이 영문 소문자·숫자 1~16자로 입력하세요");
            }
            set.add(n);
        }
        return new ArrayList<>(set);
    }

    /** 저장 경로 검증 — 1바이트 미만이거나 서블릿 상한(64MB)을 넘으면 사유가 담긴 422. */
    public static void validateMaxBytes(long maxBytes) {
        if (maxBytes < 1) {
            throw VaultException.invalid("파일당 최대 용량은 1바이트 이상이어야 합니다");
        }
        if (maxBytes > MAX_BYTES_LIMIT) {
            throw VaultException.invalid("파일당 최대 용량은 " + (MAX_BYTES_LIMIT / 1024 / 1024)
                + "MB를 넘을 수 없습니다 (서버 업로드 상한)");
        }
    }

    public Set<String> allowedExt() {
        return allowedExt;
    }

    public long maxBytes() {
        return maxBytes;
    }

    /** 확장자: 마지막 '.' 뒤 소문자. '.' 없으면 빈 문자열. */
    public static String ext(String filename) {
        String name = filename == null ? "" : filename;
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    public static boolean isImage(String ext) {
        return IMAGE_EXTS.contains(ext.toLowerCase());
    }

    /** 위반 시 VaultException.invalid(422). */
    public void check(String filename, long size) {
        String ext = ext(filename);
        if (ext.isEmpty() || !allowedExt.contains(ext)) {
            throw VaultException.invalid("허용하지 않는 파일 형식입니다: " + (ext.isEmpty() ? filename : "." + ext));
        }
        if (size <= 0) {
            throw VaultException.invalid("빈 파일은 업로드할 수 없습니다");
        }
        if (size > maxBytes) {
            throw VaultException.invalid("파일이 너무 큽니다 (최대 " + (maxBytes / 1024 / 1024) + "MB)");
        }
    }
}
