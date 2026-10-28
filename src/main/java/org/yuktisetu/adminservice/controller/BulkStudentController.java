package org.yuktisetu.adminservice.controller;


import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yuktisetu.adminservice.dto.BulkStudentCreateResponse;
import org.yuktisetu.adminservice.dto.BulkStudentRequest;
import org.yuktisetu.adminservice.dto.PendingInviteDto;
import org.yuktisetu.adminservice.dto.ResendInvitesRequest;
import org.yuktisetu.adminservice.dto.ResendInvitesResponse;
import org.yuktisetu.adminservice.service.StudentInviteService;
import java.util.List;
import org.yuktisetu.adminservice.service.BulkStudentService;
import org.yuktisetu.core.response.YuktiSetuResponse;
import org.yuktisetu.core.security.UserPrincipal;

@RequestMapping("/bulk-student")
@RestController
@AllArgsConstructor
public class BulkStudentController {

    private final BulkStudentService bulkStudentService;
    private final StudentInviteService studentInviteService;

    @PostMapping
    public ResponseEntity<YuktiSetuResponse<BulkStudentCreateResponse>> createBulkStudent(@AuthenticationPrincipal UserPrincipal userPrincipal,
                                                                       @RequestBody BulkStudentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(YuktiSetuResponse.success(bulkStudentService.createBulkStudentProfile(userPrincipal, request), 201, "Bulk student import processed"));
    }

    /** Students imported but not yet activated -- the ones whose invite may need re-sending. */
    @GetMapping("/pending-invites")
    public ResponseEntity<YuktiSetuResponse<List<PendingInviteDto>>> pendingInvites(@AuthenticationPrincipal UserPrincipal userPrincipal) {
        return ResponseEntity.ok(YuktiSetuResponse.success(studentInviteService.listPending(userPrincipal), 200, "Pending invites"));
    }

    @PostMapping("/resend-invites")
    public ResponseEntity<YuktiSetuResponse<ResendInvitesResponse>> resendInvites(@AuthenticationPrincipal UserPrincipal userPrincipal,
                                                                                  @RequestBody ResendInvitesRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                studentInviteService.resend(userPrincipal, request.getUserIds()), 200, "Invites re-sent"));
    }
}
