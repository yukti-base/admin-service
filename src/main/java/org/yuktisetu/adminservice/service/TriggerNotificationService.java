package org.yuktisetu.adminservice.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.yuktisetu.adminservice.dto.BulkStudentCreateResponse;

import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@AllArgsConstructor
public class TriggerNotificationService {

    @Async
    public void processStudentInvitesAsync(List<BulkStudentCreateResponse.BulkStudentSuccessDto> successfulStudents) {
        try {
            log.info("ASYNC: Starting invite processing for {} successful students", successfulStudents.size());
            logInviteInformation(successfulStudents);
            log.info("ASYNC: Finished invite processing for {} students", successfulStudents.size());
        } catch (Exception e) {
            log.error("ASYNC: Error processing student invites: {}", e.getMessage(), e);
        }
    }

    private void logInviteInformation(List<BulkStudentCreateResponse.BulkStudentSuccessDto> successfulStudents) {
        if (successfulStudents.isEmpty()) {
            log.info("No successful students to send invites to");
            return;
        }

        log.info("PREPARING TO SEND INVITE EMAILS TO {} STUDENTS:", successfulStudents.size());
        for (BulkStudentCreateResponse.BulkStudentSuccessDto student : successfulStudents) {
            String token = UUID.randomUUID().toString();
            String inviteLink = String.format("https://frontend.yuktisetu.com/accept-invite?token=%s", token);

            log.info("INVITE FOR STUDENT: {} {} <{}>",
                    student.getFirstName(), student.getLastName(), student.getEmail());
            log.info("  Invite Link: {}", inviteLink);
            log.info("  Email Template: Welcome to YuktiSetu! Please click the link below to set your password and activate your account.");
            log.info("  Token: {} (expires in 72 hours)", token);
        }
        log.info("FINISHED PREPARING INVITE INFORMATION FOR {} STUDENTS", successfulStudents.size());
    }
}
