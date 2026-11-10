package org.yuktisetu.adminservice.offers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.yuktisetu.adminservice.offers.OfferDtos.CompanyWithdrawal;
import org.yuktisetu.adminservice.offers.OfferDtos.DriveResults;
import org.yuktisetu.adminservice.offers.OfferDtos.WithdrawalDecision;
import org.yuktisetu.core.response.YuktiSetuResponse;
import org.yuktisetu.core.security.UserPrincipal;

/**
 * The drive's results page and the offer decisions made from it.
 *
 * Results carry phone numbers and resumes, so ground volunteers -- students
 * themselves -- are not readers. Withdrawal decisions are for staff who can run
 * the drive; a company's withdrawal is recorded by an IT admin only.
 */
@RestController
@RequiredArgsConstructor
public class DriveResultsController {

    private static final String RESULT_READERS =
            "hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN','ROLE_TNP_COORDINATOR',"
                    + "'ROLE_HOD','ROLE_FACULTY_DEPT_COORDINATOR')";

    private static final String DECIDERS =
            "hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN','ROLE_TNP_COORDINATOR')";

    private final DriveResultsService resultsService;
    private final OfferAdminService offerService;

    @GetMapping("/postings/{postingId}/results")
    @PreAuthorize(RESULT_READERS)
    public ResponseEntity<YuktiSetuResponse<DriveResults>> results(@AuthenticationPrincipal UserPrincipal actor,
                                                                   @PathVariable Long postingId) {
        return ResponseEntity.ok(YuktiSetuResponse.success(resultsService.results(actor, postingId)));
    }

    @PostMapping("/offers/withdrawal-requests/{requestId}/decision")
    @PreAuthorize(DECIDERS)
    public ResponseEntity<YuktiSetuResponse<Void>> decide(@AuthenticationPrincipal UserPrincipal actor,
                                                          @PathVariable Long requestId,
                                                          @Valid @RequestBody WithdrawalDecision decision) {
        offerService.decideWithdrawal(actor, requestId, decision.approve(), decision.note());
        return ResponseEntity.ok(YuktiSetuResponse.success(null,
                decision.approve() ? "Withdrawal approved" : "Withdrawal not approved"));
    }

    @PostMapping("/offers/{offerId}/company-withdrawal")
    @PreAuthorize("hasAuthority('ROLE_IT_ADMIN')")
    public ResponseEntity<YuktiSetuResponse<Void>> withdrawForCompany(@AuthenticationPrincipal UserPrincipal actor,
                                                                      @PathVariable Long offerId,
                                                                      @Valid @RequestBody CompanyWithdrawal request) {
        offerService.withdrawForCompany(actor, offerId, request.reason());
        return ResponseEntity.ok(YuktiSetuResponse.success(null, "Offer withdrawn on the company's behalf"));
    }
}
