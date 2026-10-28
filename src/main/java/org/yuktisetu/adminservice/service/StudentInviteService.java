package org.yuktisetu.adminservice.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.adminservice.dto.PendingInviteDto;
import org.yuktisetu.adminservice.dto.ResendInvitesResponse;
import org.yuktisetu.adminservice.exception.AdminExceptions;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.identity.db.User;
import org.yuktisetu.identity.model.RoleType;
import org.yuktisetu.identity.model.UserStatus;
import org.yuktisetu.identity.repository.UserRepository;

import java.util.List;

/**
 * Recovery path for invites that never arrived: lists students still waiting to
 * activate and re-sends their invite email with a fresh token.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StudentInviteService {

    private static final int MAX_RESEND = 500;

    private final EntityManager entityManager;
    private final UserRepository userRepository;
    private final TriggerNotificationService triggerNotificationService;

    @Transactional(readOnly = true)
    public List<PendingInviteDto> listPending(UserPrincipal principal) {
        requireStaff(principal);
        List<User> users = entityManager.createQuery("""
                        select u from User u
                         where u.isDeleted = false
                           and u.status = :status
                           and exists (select 1 from UserRoleAssignment a
                                        where a.user = u and a.role = :role and a.isActive = true)
                         order by u.createdAt, u.id
                        """, User.class)
                .setParameter("status", UserStatus.PENDING_ACTIVATION)
                .setParameter("role", RoleType.STUDENT)
                .setMaxResults(1000)
                .getResultList();

        return users.stream()
                .map(u -> PendingInviteDto.builder()
                        .userId(u.getId())
                        .firstName(u.getFirstName())
                        .lastName(u.getLastName())
                        .email(u.getEmail())
                        .createdAt(u.getCreatedAt())
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public ResendInvitesResponse resend(UserPrincipal principal, List<Long> userIds) {
        requireStaff(principal);
        if (userIds == null || userIds.isEmpty()) {
            throw new AdminExceptions.InvalidRequestException("Select at least one student to re-invite");
        }
        if (userIds.size() > MAX_RESEND) {
            throw new AdminExceptions.InvalidRequestException(
                    "Cannot re-invite more than " + MAX_RESEND + " students at once");
        }

        List<User> eligible = userRepository.findAllById(userIds).stream()
                .filter(u -> !u.isDeleted() && u.getStatus() == UserStatus.PENDING_ACTIVATION)
                .toList();
        List<Long> eligibleIds = eligible.stream().map(User::getId).toList();
        List<Long> skipped = userIds.stream().filter(id -> !eligibleIds.contains(id)).distinct().toList();

        if (!eligible.isEmpty()) {
            log.info("User {} re-sending invites to {} students ({} skipped)",
                    principal.userId(), eligible.size(), skipped.size());
            triggerNotificationService.sendInvites(eligible);
        }
        return ResendInvitesResponse.builder().sentCount(eligible.size()).skippedUserIds(skipped).build();
    }

    private void requireStaff(UserPrincipal principal) {
        if (principal.hasRole(String.valueOf(RoleType.STUDENT))) {
            throw new AdminExceptions.InsufficientAuthorityException();
        }
    }
}
