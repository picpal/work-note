package com.worknote.auth.totp;

/** 메일 발송 추상화. SmtpMailSender / NoopMailSender 중 MailConfig 팩토리가 단일 빈으로 등록. */
public interface MailSender {
    boolean available();
    void send(String to, String subject, String body);
}
