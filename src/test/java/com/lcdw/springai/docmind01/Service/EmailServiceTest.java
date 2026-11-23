package com.lcdw.springai.docmind01.Service;

import com.lcdw.springai.docmind01.Execption.Auth_EXE;
import com.lcdw.springai.docmind01.Service.Security.EmailService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmailServiceTest {
    @Test void explainsProviderIpRejectionWithoutExposingSmtpDetails() {
        var sender = mock(JavaMailSender.class);
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        var service = new EmailService(provider);
        ReflectionTestUtils.setField(service, "fromAddress", "sender@example.test");
        ReflectionTestUtils.setField(service, "frontendUrl", "http://localhost:8081");
        when(sender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        doThrow(new MailAuthenticationException("525 5.7.1 Unauthorized IP address"))
                .when(sender).send(any(MimeMessage.class));
        assertThatThrownBy(() -> service.sendVerificationEmail("recipient@example.test", "Researcher", "test-token"))
                .isInstanceOf(Auth_EXE.class).hasMessageContaining("IP allowlist")
                .hasMessageContaining("Brevo").hasMessageNotContaining("525");
    }
}
