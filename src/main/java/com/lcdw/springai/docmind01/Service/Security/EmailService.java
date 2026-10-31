package com.lcdw.springai.docmind01.Service.Security;

import com.lcdw.springai.docmind01.Execption.Auth_EXE;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class EmailService {

    /**
     * Optional on purpose: spring-boot-starter-mail only creates a JavaMailSender when
     * spring.mail.host is set, and this app must still boot without it.
     */
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    /**
     * Prefer an explicit mail.smtp.from (the Brevo login address is not a mailbox);
     * fall back to the SMTP username.
     */
    @Value("${spring.mail.properties.mail.smtp.from:${spring.mail.username:}}")
    private String fromAddress;

    @Value("${app.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    public EmailService(ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.mailSenderProvider = mailSenderProvider;
    }

    public void sendVerificationEmail(String to, String username, String rawToken) {
        String link = frontendUrl.replaceAll("/+$", "") + "/verify?token=" + rawToken;

        String html = """
                <div style="font-family:'Plus Jakarta Sans',Segoe UI,Arial,sans-serif;background:#F5F7FB;padding:32px">
                  <div style="max-width:520px;margin:0 auto;background:#ffffff;border:1px solid #E4E9F2;border-radius:14px;overflow:hidden">
                    <div style="background:#283655;padding:24px 28px">
                      <div style="color:#ffffff;font-size:20px;font-weight:800">DocMind</div>
                      <div style="color:#A5D0FF;font-size:13px;margin-top:4px">Confirm your email to start asking your documents</div>
                    </div>
                    <div style="padding:28px;color:#2A3342;font-size:15px;line-height:1.6">
                      <p>Hi %s,</p>
                      <p>Click the button below to verify your email address. The link expires in 24 hours.</p>
                      <p style="margin:28px 0">
                        <a href="%s" style="background:#3399FF;color:#ffffff;text-decoration:none;padding:12px 24px;border-radius:8px;font-weight:700;display:inline-block">Verify my email</a>
                      </p>
                      <p style="font-size:13px;color:#63708A">If the button does not work, paste this into your browser:<br>
                      <span style="word-break:break-all">%s</span></p>
                      <p style="font-size:13px;color:#63708A">If you did not create a DocMind account, you can ignore this email.</p>
                    </div>
                  </div>
                </div>
                """.formatted(escapeHtml(username), link, link);

        send(to, "Verify your DocMind email", html);
    }

    private void send(String to, String subject, String html) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();

        if (mailSender == null) {
            throw new Auth_EXE("Email delivery is not configured. Add spring.mail.host, spring.mail.port, "
                    + "spring.mail.username and spring.mail.password to src/main/resources/application-dev.yml, "
                    + "then restart the backend and register again.");
        }

        if (fromAddress == null || fromAddress.isBlank()) {
            throw new Auth_EXE("spring.mail.username is not set, so there is no sender address to mail from.");
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);

            mailSender.send(message);
            log.info("sent '{}' to {}", subject, to);
        } catch (MessagingException e) {
            throw new Auth_EXE("Could not build the verification email: " + e.getMessage(), e);
        } catch (MailException e) {
            throw new Auth_EXE("The SMTP server refused the verification email: " + e.getMessage(), e);
        }
    }

    // The username is user-supplied and lands inside HTML, so it must not be able to inject markup.
    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
