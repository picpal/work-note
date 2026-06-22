package com.worknote.pii;

import java.util.ArrayList;
import java.util.Comparator;
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

    /** 매치 위치 포함 — 라인/오프셋 산출용. start/end는 text 내 문자 인덱스. */
    public record Match(PiiType type, int start, int end, String value) {}

    /** 모든 패턴의 매치를 위치와 함께 수집(start 오름차순). CARD만 Luhn 통과. */
    public static List<Match> scanMatches(String text) {
        List<Match> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        collectMatches(RRN, PiiType.RRN, text, out);
        collectMatches(BIZ, PiiType.BIZ, text, out);
        collectCardMatches(text, out);
        collectMatches(PHONE, PiiType.PHONE, text, out);
        collectMatches(EMAIL, PiiType.EMAIL, text, out);
        collectMatches(PASSPORT, PiiType.PASSPORT, text, out);
        collectMatches(DRIVER, PiiType.DRIVER, text, out);
        out.sort(Comparator.comparingInt(Match::start));
        return out;
    }

    private static void collectMatches(Pattern p, PiiType t, String text, List<Match> out) {
        Matcher m = p.matcher(text);
        while (m.find()) out.add(new Match(t, m.start(), m.end(), m.group()));
    }

    private static void collectCardMatches(String text, List<Match> out) {
        Matcher m = CARD.matcher(text);
        while (m.find()) {
            if (luhn(m.group().replaceAll("[ -]", ""))) {
                out.add(new Match(PiiType.CARD, m.start(), m.end(), m.group()));
            }
        }
    }

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
