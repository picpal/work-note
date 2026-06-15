package com.worknote.pii;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 표준 한국 PII 탐지(순수). 경고용 기능이라 오탐 허용이 전제 —
 * RRN/BIZ는 형식만으로 탐지(체크섬 미적용)해 가짜·테스트 번호도 잡는다.
 * CARD만 Luhn으로 무작위 16자리 노이즈를 억제. 유선전화·계좌는 의도적 제외.
 */
public final class PiiDetector {
    private PiiDetector() {}

    private static final Pattern RRN      = Pattern.compile("(?<!\\d)\\d{6}[-\\s]?[1-8]\\d{6}(?!\\d)");
    private static final Pattern PHONE    = Pattern.compile("(?<!\\d)01[016789][-\\s]?\\d{3,4}[-\\s]?\\d{4}(?!\\d)");
    private static final Pattern EMAIL    = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern CARD     = Pattern.compile("(?<!\\d)(?:\\d[ -]?){15}\\d(?!\\d)");
    private static final Pattern BIZ      = Pattern.compile("(?<!\\d)\\d{3}-\\d{2}-\\d{5}(?!\\d)");
    private static final Pattern PASSPORT = Pattern.compile("(?<![A-Z0-9])[A-Z]\\d{8}(?![A-Z0-9])");
    private static final Pattern DRIVER   = Pattern.compile("(?<!\\d)\\d{2}[-\\s]?\\d{2}[-\\s]?\\d{6}[-\\s]?\\d{2}(?!\\d)");

    /** 탐지 결과 — 유형 집합 + 매치된 원문 스팬(값 기준 예외 비교용). */
    public record Scan(Set<PiiType> types, List<String> spans) {}

    public static Set<PiiType> detect(String text) {
        return scan(text).types();
    }

    public static Scan scan(String text) {
        EnumSet<PiiType> types = EnumSet.noneOf(PiiType.class);
        List<String> spans = new ArrayList<>();
        if (text == null || text.isEmpty()) return new Scan(types, spans);
        collect(RRN, PiiType.RRN, text, types, spans);
        collect(BIZ, PiiType.BIZ, text, types, spans);
        collectCard(text, types, spans);
        collect(PHONE, PiiType.PHONE, text, types, spans);
        collect(EMAIL, PiiType.EMAIL, text, types, spans);
        collect(PASSPORT, PiiType.PASSPORT, text, types, spans);
        collect(DRIVER, PiiType.DRIVER, text, types, spans);
        return new Scan(types, spans);
    }

    private static void collect(Pattern p, PiiType t, String text, Set<PiiType> types, List<String> spans) {
        Matcher m = p.matcher(text);
        boolean any = false;
        while (m.find()) { spans.add(m.group()); any = true; }
        if (any) types.add(t);
    }

    private static void collectCard(String text, Set<PiiType> types, List<String> spans) {
        Matcher m = CARD.matcher(text);
        boolean any = false;
        while (m.find()) {
            if (luhn(m.group().replaceAll("[ -]", ""))) { spans.add(m.group()); any = true; }
        }
        if (any) types.add(PiiType.CARD);
    }

    private static boolean luhn(String d) {
        int sum = 0; boolean alt = false;
        for (int i = d.length() - 1; i >= 0; i--) {
            int n = d.charAt(i) - '0';
            if (alt) { n *= 2; if (n > 9) n -= 9; }
            sum += n; alt = !alt;
        }
        return sum % 10 == 0;
    }
}
