package org.yuktisetu.adminservice.controller;


import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yuktisetu.adminservice.dto.BulkStudentCreateResponse;
import org.yuktisetu.adminservice.dto.BulkStudentRequest;
import org.yuktisetu.adminservice.service.BulkStudentService;
import org.yuktisetu.core.response.YuktiSetuResponse;
import org.yuktisetu.core.security.UserPrincipal;

@RequestMapping("/bulk-student")
@RestController
@AllArgsConstructor
public class BulkStudentController {

    private final BulkStudentService bulkStudentService;

    @PostMapping
    public ResponseEntity<YuktiSetuResponse<BulkStudentCreateResponse>> createBulkStudent(@AuthenticationPrincipal UserPrincipal userPrincipal,
                                                                       @RequestBody BulkStudentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(YuktiSetuResponse.success(bulkStudentService.createBulkStudentProfile(userPrincipal, request), 201, "Bulk student import processed"));
    }
}
