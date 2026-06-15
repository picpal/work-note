package com.worknote.pii;

import java.util.EnumSet;
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

    public static Set<PiiType> detect(String text) {
        EnumSet<PiiType> found = EnumSet.noneOf(PiiType.class);
        if (text == null || text.isEmpty()) return found;
        if (RRN.matcher(text).find()) found.add(PiiType.RRN);
        if (BIZ.matcher(text).find()) found.add(PiiType.BIZ);
        if (anyCard(text)) found.add(PiiType.CARD);
        if (PHONE.matcher(text).find()) found.add(PiiType.PHONE);
        if (EMAIL.matcher(text).find()) found.add(PiiType.EMAIL);
        if (PASSPORT.matcher(text).find()) found.add(PiiType.PASSPORT);
        if (DRIVER.matcher(text).find()) found.add(PiiType.DRIVER);
        return found;
    }

    private static boolean anyCard(String text) {
        Matcher m = CARD.matcher(text);
        while (m.find()) {
            if (luhn(m.group().replaceAll("[ -]", ""))) return true;
        }
        return false;
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
