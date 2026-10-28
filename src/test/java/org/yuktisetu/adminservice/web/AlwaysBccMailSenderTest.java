package org.yuktisetu.adminservice.web;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.yuktisetu.core.notification.config.AlwaysBccMailSender;
import org.yuktisetu.core.notification.config.EmailProperties;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every mail, from every service, must carry the audit address as BCC. The guarantee
 * lives in the single JavaMailSender, so these tests pin both the stamping logic and
 * the fact that the sender actually wired into a service IS that sender.
 */
@SpringBootTest
@ActiveProfiles("test")
class AlwaysBccMailSenderTest {

    private static final String AUDIT = "hardikmahajan97@gmail.com";

    @Autowired JavaMailSender wiredSender;
    @Autowired EmailProperties emailProperties;

    private static MimeMessage mail(String to) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress("noreply@example.test"));
        if (to != null) {
            m.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        }
        m.setSubject("hello");
        m.setText("body");
        return m;
    }

    private static List<String> bcc(MimeMessage m) throws Exception {
        var a = m.getRecipients(Message.RecipientType.BCC);
        return a == null ? List.of() : java.util.Arrays.stream(a).map(x -> ((InternetAddress) x).getAddress()).toList();
    }

    @Test
    @DisplayName("the sender wired into the service is the always-BCC one, defaulting to the audit address")
    void wiredSenderIsTheAlwaysBccSender() {
        assertInstanceOf(AlwaysBccMailSender.class, wiredSender);
        assertEquals(List.of(AUDIT), emailProperties.getAlwaysBcc(),
                "the default must hold even though no properties file mentions it");
    }

    @Test
    @DisplayName("an ordinary mail gets the audit address as BCC")
    void addsTheAuditAddress() throws Exception {
        MimeMessage m = mail("student@example.test");
        AlwaysBccMailSender.addBcc(m, List.of(AUDIT));
        assertEquals(List.of(AUDIT), bcc(m));
    }

    @Test
    @DisplayName("existing BCC recipients are kept, and the audit address is added beside them")
    void keepsExistingBcc() throws Exception {
        MimeMessage m = mail("student@example.test");
        m.addRecipient(Message.RecipientType.BCC, new InternetAddress("other@example.test"));
        AlwaysBccMailSender.addBcc(m, List.of(AUDIT));
        assertTrue(bcc(m).containsAll(List.of("other@example.test", AUDIT)));
    }

    @Test
    @DisplayName("no duplicate copy when the audit address is already a recipient, whatever its case")
    void noDuplicateWhenAlreadyARecipient() throws Exception {
        MimeMessage toAudit = mail("HardikMahajan97@Gmail.com");
        AlwaysBccMailSender.addBcc(toAudit, List.of(AUDIT));
        assertTrue(bcc(toAudit).isEmpty(), "already in To, so not added again");

        MimeMessage twice = mail("student@example.test");
        AlwaysBccMailSender.addBcc(twice, List.of(AUDIT));
        AlwaysBccMailSender.addBcc(twice, List.of(AUDIT));
        assertEquals(1, bcc(twice).size(), "stamping twice still yields one copy");
    }

    @Test
    @DisplayName("a bad audit address or an empty list never breaks the mail")
    void neverBreaksTheMail() throws Exception {
        MimeMessage m = mail("student@example.test");
        assertDoesNotThrow(() -> AlwaysBccMailSender.addBcc(m, List.of("not an address <<")));
        assertDoesNotThrow(() -> AlwaysBccMailSender.addBcc(m, List.of()));
        assertDoesNotThrow(() -> AlwaysBccMailSender.addBcc(m, null));
        assertEquals("student@example.test",
                ((InternetAddress) m.getRecipients(Message.RecipientType.TO)[0]).getAddress());
    }

    @Test
    @DisplayName("send() stamps the message before it reaches SMTP, via the real doSend path")
    void sendStampsBeforeTransport() throws Exception {
        AlwaysBccMailSender sender = new AlwaysBccMailSender(List.of(AUDIT));
        sender.setHost("127.0.0.1");
        sender.setPort(1);                       // nothing listens: the SMTP step fails, which is fine here
        MimeMessage m = new MimeMessage(sender.getSession());
        m.setFrom(new InternetAddress("noreply@example.test"));
        m.setRecipient(Message.RecipientType.TO, new InternetAddress("student@example.test"));
        m.setSubject("x");
        m.setText("x");

        assertThrows(MailException.class, () -> sender.send(m));
        assertEquals(List.of(AUDIT), bcc(m), "the audit address was added before the transport was attempted");
    }
}
