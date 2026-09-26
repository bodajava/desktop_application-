package com.examhalls.service;

import com.examhalls.config.AppSettings;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Sends a newly-imported student their login credentials by email (Gmail SMTP + App Password).
 * Disabled (silently, {@link #isConfigured()} false) when no {@code mail.smtp.username} is set,
 * so schools that skip this feature never see a failure from it.
 */
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final String host;
    private final int port;
    private final String username;
    private final String appPassword;

    public EmailService() {
        this.host = AppSettings.get("mail.smtp.host", "smtp.gmail.com");
        this.port = AppSettings.getInt("mail.smtp.port", 587);
        this.username = AppSettings.get("mail.smtp.username", "");
        this.appPassword = AppSettings.get("mail.smtp.appPassword", "");
    }

    public boolean isConfigured() {
        return !username.isBlank() && !appPassword.isBlank();
    }

    /**
     * @throws MessagingException on any SMTP failure; callers decide whether that should stop
     *                            the rest of an import batch (it should not).
     */
    public void sendStudentCredentials(String toEmail, String studentName, String username, String initialPassword)
            throws MessagingException {
        if (!isConfigured()) {
            throw new IllegalStateException("Mail is not configured (mail.smtp.username is blank)");
        }
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));

        Session session = Session.getInstance(props, new jakarta.mail.Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(EmailService.this.username, appPassword);
            }
        });

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(this.username));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
        message.setSubject("Your Exam Halls account / بيانات حسابك في نظام قاعات الامتحانات");
        message.setText("""
                Hello %s,

                An account was created for you in the Exam Halls system.

                Username: %s
                Temporary password: %s

                You will be asked to choose a new password the first time you sign in.

                ------------------------------------------------------------

                مرحبًا %s،

                تم إنشاء حساب لك في نظام إدارة قاعات الامتحانات.

                اسم المستخدم: %s
                كلمة المرور المؤقتة: %s

                هيُطلب منك اختيار كلمة مرور جديدة عند أول تسجيل دخول.
                """.formatted(studentName, username, initialPassword, studentName, username, initialPassword),
                "UTF-8");

        Transport.send(message);
        log.info("Sent login credentials by email to {}", toEmail);
    }
}