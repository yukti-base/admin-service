package org.yuktisetu.adminservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.yuktisetu.adminservice.dto.BulkStudentCreateResponse;
import org.yuktisetu.core.notification.model.InviteRecipient;
import org.yuktisetu.core.notification.service.EmailService;
import org.yuktisetu.db.User;
import org.yuktisetu.repository.UserRepository;

import java.time.Duration;
import java.util.ArrayList;
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

    @Value("${app.notification.debug:false}")
    private boolean debug;

    private static final String PREFIX = "invite:";
    private static final Duration TTL = Duration.ofHours(72);

    @Async
    public void processStudentInvitesAsync(List<Long> userIds, List<BulkStudentCreateResponse.BulkStudentSuccessDto> successfulStudents) {
        try {
            log.info("ASYNC: Starting invite processing for {} successful students", successfulStudents.size());

            Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                    .collect(Collectors.toMap(User::getId, user -> user));

            sendBulkInvites(userMap);
            log.info("ASYNC: Finished invite processing for {} students", successfulStudents.size());
        } catch (Exception e) {
            log.error("ASYNC: Error processing student invites: {}", e.getMessage(), e);
        }
    }

    private void sendBulkInvites(Map<Long, User> userMap) {
        if (userMap.isEmpty()) {
            log.info("No successful students to send invites to");
            return;
        }

        List<InviteRecipient> recipients = new ArrayList<>();

        for (Long studentId : userMap.keySet()) {
            User user = userMap.get(studentId);
            String token = issue(studentId);
            String fullName = user.getFirstName() + " " + user.getLastName();

            recipients.add(new InviteRecipient(user.getEmail(), fullName, token));

            if (debug) {
                String inviteLink = String.format("https://frontend.yuktisetu.com/accept-invite?token=%s", token);
                log.info("QUEUED INVITE FOR: {} <{}>", fullName, user.getEmail());
                log.info("  Invite Link: {}", inviteLink);
                log.info("  Token: {} (expires in 72 hours)", token);
            }
        }

        log.info("BULK SENDING {} INVITE EMAILS", recipients.size());

        try {
            emailService.sendBulkInviteEmails(recipients);
            log.info("BULK SEND COMPLETE FOR {} STUDENTS", recipients.size());
        } catch (Exception e) {
            log.error("Bulk invite send failed: {}", e.getMessage(), e);
        }
    }

    public String issue(Long userId) {
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(PREFIX + token, userId.toString(), TTL);
        return token;
    }
}