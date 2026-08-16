package org.yuktisetu.adminservice.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.yuktisetu.adminservice.dto.BulkStudentCreateResponse;
import org.yuktisetu.db.User;
import org.yuktisetu.repository.UserRepository;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@AllArgsConstructor
public class TriggerNotificationService {

    private final UserRepository userRepository;
    private final StringRedisTemplate redis;
    private static final String PREFIX = "invite:";
    private static final Duration TTL = Duration.ofHours(72);

    @Async
    public void processStudentInvitesAsync(List<Long> userIds, List<BulkStudentCreateResponse.BulkStudentSuccessDto> successfulStudents) {
        try {
            log.info("ASYNC: Starting invite processing for {} successful students", successfulStudents.size());

            Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                    .collect(Collectors.toMap(User::getId, user -> user));

            logInviteInformation(userMap);
            log.info("ASYNC: Finished invite processing for {} students", successfulStudents.size());
        } catch (Exception e) {
            log.error("ASYNC: Error processing student invites: {}", e.getMessage(), e);
        }
    }

    private void logInviteInformation(Map<Long, User> userMap) {
        if (userMap.isEmpty()) {
            log.info("No successful students to send invites to");
            return;
        }

        log.info("PREPARING TO SEND INVITE EMAILS TO {} STUDENTS:", userMap.size());
        for (Long student : userMap.keySet()) {
            User user = userMap.get(student);
            String token = issue(student);
            String inviteLink = String.format("https://frontend.yuktisetu.com/accept-invite?token=%s", token);

            log.info("INVITE FOR STUDENT: {} {} <{}>",
                    user.getFirstName(), user.getLastName(), user.getEmail());
            log.info("  Invite Link: {}", inviteLink);
            log.info("  Email Template: Welcome to YuktiSetu! Please click the link below to set your password and activate your account.");
            log.info("  Token: {} (expires in 72 hours)", token);
        }
        log.info("FINISHED PREPARING INVITE INFORMATION FOR {} STUDENTS", userMap.size());
    }

    public String issue(Long userId) {
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(PREFIX + token, userId.toString(), TTL);
        return token;
    }
}
