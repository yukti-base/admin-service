package org.yuktisetu.adminservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.yuktisetu.core.notification.config.EmailProperties;
import org.yuktisetu.core.notification.model.InviteRecipient;
import org.yuktisetu.core.notification.service.EmailService;
import org.yuktisetu.identity.db.User;
import org.yuktisetu.identity.repository.UserRepository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class TriggerNotificationService {

    private final UserRepository userRepository;
    private final StringRedisTemplate redis;
    private final EmailService emailService;
    private final EmailProperties emailProperties;

    @Value("${app.notification.debug:false}")
    private boolean debug;

    private static final String PREFIX = "invite:";
    private static final Duration TTL = Duration.ofHours(72);

    /**
     * Takes IDs only. An entity (or a DTO built from one) handed across the thread
     * boundary is a snapshot from another session: its lazy associations cannot
     * load here, and it may be stale. The worker reads what it needs itself.
     */
    @Async
    public void processStudentInvitesAsync(List<Long> userIds) {
        try {
            log.info("ASYNC: Starting invite processing for {} students", userIds.size());

            Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                    .collect(Collectors.toMap(User::getId, user -> user));

            if (userMap.size() != userIds.size()) {
                log.warn("ASYNC: asked to invite {} users but only {} are visible in the database",
                        userIds.size(), userMap.size());
            }
            sendBulkInvites(userMap);
            log.info("ASYNC: Finished invite processing for {} students", userIds.size());
        } catch (Exception e) {
            log.error("ASYNC: Error processing student invites: {}", e.getMessage(), e);
        }
    }

    private void sendBulkInvites(Map<Long, User> userMap) {
        if (userMap.isEmpty()) {
            log.info("No successful students to send invites to");
            return;
        }
        sendInvites(userMap.values());
    }

    /**
     * Issues a fresh token for each user and emails the invite. Throws if the mail
     * send fails, so a caller that must report the outcome (the resend endpoint)
     * can; the post-import path catches and logs it.
     */
    public void sendInvites(Collection<User> users) {
        List<InviteRecipient> recipients = new ArrayList<>();

        for (User user : users) {
            String token = issue(user.getId());
            String fullName = user.getLastName() == null
                    ? user.getFirstName() : user.getFirstName() + " " + user.getLastName();

            recipients.add(new InviteRecipient(user.getEmail(), fullName, token));

            if (debug) {
                String inviteLink = String.format("%s?token=%s", emailProperties.getBaseInviteUrl(), token);
                log.info("QUEUED INVITE FOR: {} <{}>", fullName, user.getEmail());
                log.info("  Invite Link: {}", inviteLink);
                log.info("  Token: {} (expires in 72 hours)", token);
            }
        }

        log.info("BULK SENDING {} INVITE EMAILS", recipients.size());
        emailService.sendBulkInviteEmails(recipients);
        log.info("BULK SEND COMPLETE FOR {} STUDENTS", recipients.size());
    }

    public String issue(Long userId) {
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(PREFIX + token, userId.toString(), TTL);
        return token;
    }
}