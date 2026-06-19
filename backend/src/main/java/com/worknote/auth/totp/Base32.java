package com.worknote.auth.totp;

/** RFC 4648 Base32 (대문자 알파벳, 패딩 없이 출력 / 입력 시 패딩·소문자 허용). TOTP 시드 인코딩 전용. */
public final class Base32 {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private Base32() {}

    public static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                sb.append(ALPHABET.charAt((buffer >> bits) & 0x1f));
            }
        }
        if (bits > 0) sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1f));
        return sb.toString();
    }

    public static byte[] decode(String s) {
        String clean = s.trim().replace("=", "").toUpperCase();
        int buffer = 0, bits = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < clean.length(); i++) {
            int v = ALPHABET.indexOf(clean.charAt(i));
            if (v < 0) throw new IllegalArgumentException("Base32 문자 아님: " + clean.charAt(i));
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xff);
            }
        }
        return out.toByteArray();
    }
}
