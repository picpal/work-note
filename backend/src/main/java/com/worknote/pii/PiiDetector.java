package com.worknote.pii;

import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 표준 한국 PII 탐지(순수). 오탐 억제 위해 RRN/CARD/BIZ는 체크섬 검증. 유선전화·계좌는 의도적 제외. */
public final class PiiDetector {
    private PiiDetector() {}

    private static final Pattern RRN      = Pattern.compile("(?<!\\d)(\\d{2})(\\d{2})(\\d{2})[-\\s]?([1-8])(\\d{6})(?!\\d)");
    private static final Pattern PHONE    = Pattern.compile("(?<!\\d)01[016789][-\\s]?\\d{3,4}[-\\s]?\\d{4}(?!\\d)");
    private static final Pattern EMAIL    = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern CARD     = Pattern.compile("(?<!\\d)(?:\\d[ -]?){15}\\d(?!\\d)");
    private static final Pattern BIZ      = Pattern.compile("(?<!\\d)(\\d{3})-(\\d{2})-(\\d{5})(?!\\d)");
    private static final Pattern PASSPORT = Pattern.compile("(?<![A-Z0-9])[A-Z]\\d{8}(?![A-Z0-9])");
    private static final Pattern DRIVER   = Pattern.compile("(?<!\\d)\\d{2}[-\\s]?\\d{2}[-\\s]?\\d{6}[-\\s]?\\d{2}(?!\\d)");

    public static Set<PiiType> detect(String text) {
        EnumSet<PiiType> found = EnumSet.noneOf(PiiType.class);
        if (text == null || text.isEmpty()) return found;
        if (anyRrn(text)) found.add(PiiType.RRN);
        if (anyBiz(text)) found.add(PiiType.BIZ);
        if (anyCard(text)) found.add(PiiType.CARD);
        if (PHONE.matcher(text).find()) found.add(PiiType.PHONE);
        if (EMAIL.matcher(text).find()) found.add(PiiType.EMAIL);
        if (PASSPORT.matcher(text).find()) found.add(PiiType.PASSPORT);
        if (DRIVER.matcher(text).find()) found.add(PiiType.DRIVER);
        return found;
    }

    private static boolean anyRrn(String text) {
        Matcher m = RRN.matcher(text);
        while (m.find()) {
            String digits = (m.group(1) + m.group(2) + m.group(3) + m.group(4) + m.group(5));
            if (rrnChecksum(digits)) return true;
        }
        return false;
    }

    private static boolean rrnChecksum(String d) {
        int[] w = {2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5};
        int sum = 0;
        for (int i = 0; i < 12; i++) sum += (d.charAt(i) - '0') * w[i];
        int check = (11 - (sum % 11)) % 10;
        return check == (d.charAt(12) - '0');
    }

    private static boolean anyBiz(String text) {
        Matcher m = BIZ.matcher(text);
        while (m.find()) {
            String d = (m.group(1) + m.group(2) + m.group(3));
            if (bizChecksum(d)) return true;
        }
        return false;
    }

    private static boolean bizChecksum(String d) {
        int[] w = {1, 3, 7, 1, 3, 7, 1, 3, 5};
        int sum = 0;
        for (int i = 0; i < 9; i++) sum += (d.charAt(i) - '0') * w[i];
        sum += ((d.charAt(8) - '0') * 5) / 10;
        int check = (10 - (sum % 10)) % 10;
        return check == (d.charAt(9) - '0');
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
