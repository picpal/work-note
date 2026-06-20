package com.worknote.auth.totp;

/** SMTP 미설정 시(host 빈값) MailConfig 팩토리가 이 구현을 등록. @Component 없음 — 팩토리가 등록. */
public class NoopMailSender implements MailSender {
    public boolean available() { return false; }
    public void send(String to, String subject, String body) { /* no-op: 이메일 복구 비활성 */ }
}
