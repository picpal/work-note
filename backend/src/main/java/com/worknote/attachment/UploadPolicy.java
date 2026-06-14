package com.worknote.attachment;

import com.worknote.vault.VaultException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 업로드 허용 정책 — 확장자 allowlist + 파일당 최대 바이트. 순수 검사 로직(테스트 용이). */
public final class UploadPolicy {
    private static final Set<String> IMAGE_EXTS = Set.of("png", "jpg", "jpeg", "gif", "webp");

    private final Set<String> allowedExt;
    private final long maxBytes;

    private UploadPolicy(Set<String> allowedExt, long maxBytes) {
        this.allowedExt = allowedExt;
        this.maxBytes = maxBytes;
    }

    public static UploadPolicy of(List<String> exts, long maxBytes) {
        Set<String> set = new LinkedHashSet<>();
        for (String e : exts) {
            String n = e.trim().toLowerCase().replaceFirst("^\\.", "");
            if (!n.isEmpty()) {
                set.add(n);
            }
        }
        return new UploadPolicy(set, maxBytes);
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
