package com.worknote.auth.totp;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.util.Properties;

/** SMTP host 설정 시 MailConfig 팩토리가 이 구현을 등록. @Component 없음 — 팩토리가 등록. */
public class SmtpMailSender implements MailSender {
    private final String host, from, user, password;
    private final int port;
    private final boolean starttls;

    public SmtpMailSender(String host, int port, String from, String user, String password, boolean starttls) {
        this.host = host; this.port = port; this.from = from;
        this.user = user; this.password = password; this.starttls = starttls;
    }

    public boolean available() { return true; }

    public void send(String to, String subject, String body) {
        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        if (starttls) props.put("mail.smtp.starttls.enable", "true");
        boolean auth = !user.isBlank();
        props.put("mail.smtp.auth", String.valueOf(auth));
        Session session = auth
            ? Session.getInstance(props, new Authenticator() {
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(user, password);
                }})
            : Session.getInstance(props);
        try {
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(from));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            msg.setSubject(subject, "UTF-8");
            msg.setText(body, "UTF-8");
            Transport.send(msg);
        } catch (MessagingException e) {
            throw new IllegalStateException("메일 발송 실패", e);
        }
    }
}
