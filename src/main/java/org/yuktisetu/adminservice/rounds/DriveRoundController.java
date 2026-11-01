package org.yuktisetu.adminservice.rounds;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yuktisetu.adminservice.rounds.RoundDtos.CorrectionRequest;
import org.yuktisetu.adminservice.rounds.RoundDtos.EmailResolution;
import org.yuktisetu.adminservice.rounds.RoundDtos.EndDriveRequest;
import org.yuktisetu.adminservice.rounds.RoundDtos.EndDriveResult;
import org.yuktisetu.adminservice.rounds.RoundDtos.HistoryEntry;
import org.yuktisetu.adminservice.rounds.RoundDtos.MarkRequest;
import org.yuktisetu.adminservice.rounds.RoundDtos.MarkResult;
import org.yuktisetu.adminservice.rounds.RoundDtos.PublishRoundRequest;
import org.yuktisetu.adminservice.rounds.RoundDtos.PublishRoundResult;
import org.yuktisetu.adminservice.rounds.RoundDtos.ResolveEmailsRequest;
import org.yuktisetu.adminservice.rounds.RoundDtos.RoundRequest;
import org.yuktisetu.adminservice.rounds.RoundDtos.RoundSheet;
import org.yuktisetu.adminservice.rounds.RoundDtos.RoundView;
import org.yuktisetu.adminservice.rounds.RoundDtos.RoundsOverview;
import org.yuktisetu.adminservice.rounds.RoundDtos.SheetRow;
import org.yuktisetu.core.response.YuktiSetuResponse;
import org.yuktisetu.core.security.UserPrincipal;

import java.util.List;

/**
 * A drive's rounds after the shortlist (decided 2026-10-30).
 *
 * In admin-service with every other administrative operation, so drive-day
 * marking never queues behind drive-service while students refresh their
 * results. The @PreAuthorize gates are coarse; PlacementDrivePolicy does the
 * college scoping:
 *  - read: the pool readers and ground volunteers of any targeted college;
 *  - mark: volunteers, coordinators and admins of any targeted college, for
 *    every candidate in the drive;
 *  - define, publish, correct, end: coordinator or above over every target.
 */
@RestController
@RequestMapping("/postings/{postingId}/rounds")
@RequiredArgsConstructor
public class DriveRoundController {

    private static final String READERS =
            "hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN','ROLE_TNP_COORDINATOR',"
                    + "'ROLE_GROUND_VOLUNTEER','ROLE_HOD','ROLE_FACULTY_DEPT_COORDINATOR')";

    private static final String MARKERS =
            "hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN','ROLE_TNP_COORDINATOR',"
                    + "'ROLE_GROUND_VOLUNTEER')";

    private static final String MANAGERS =
            "hasAnyAuthority('ROLE_IT_ADMIN','ROLE_TNP_SUPER_ADMIN','ROLE_TNP_COLLEGE_ADMIN','ROLE_TNP_COORDINATOR')";

    private final DriveRoundService roundService;
    private final RoundMarkingService markingService;
    private final RoundPublishingService publishingService;
    private final DriveEndService endService;

    @GetMapping
    @PreAuthorize(READERS)
    public ResponseEntity<YuktiSetuResponse<RoundsOverview>> overview(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable Long postingId) {
        return ResponseEntity.ok(YuktiSetuResponse.success(roundService.overview(actor, postingId)));
    }

    @PostMapping
    @PreAuthorize(MANAGERS)
    public ResponseEntity<YuktiSetuResponse<RoundView>> add(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable Long postingId,
            @RequestBody @Valid RoundRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(roundService.add(actor, postingId, request), "Round added"));
    }

    @PatchMapping("/{roundId}")
    @PreAuthorize(MANAGERS)
    public ResponseEntity<YuktiSetuResponse<RoundView>> edit(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable Long postingId, @PathVariable Long roundId,
            @RequestBody @Valid RoundRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                roundService.edit(actor, postingId, roundId, request), "Round saved"));
    }

    @DeleteMapping("/{roundId}")
    @PreAuthorize(MANAGERS)
    public ResponseEntity<YuktiSetuResponse<Void>> delete(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable Long postingId, @PathVariable Long roundId) {
        roundService.delete(actor, postingId, roundId);
        return ResponseEntity.ok(YuktiSetuResponse.success(null, "Round removed"));
    }

    @GetMapping("/{roundId}/sheet")
    @PreAuthorize(READERS)
    public ResponseEntity<YuktiSetuResponse<RoundSheet>> sheet(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable Long postingId, @PathVariable Long roundId,
            @RequestParam(required = false) Long knownVersion) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                roundService.sheet(actor, postingId, roundId, knownVersion)));
    }

    @PostMapping("/{roundId}/marks")
    @PreAuthorize(MARKERS)
    public ResponseEntity<YuktiSetuResponse<MarkResult>> mark(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable Long postingId, @PathVariable Long roundId,
            @RequestBody @Valid MarkRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(markingService.mark(actor, postingId, roundId, request)));
    }

    @PostMapping("/{roundId}/resolve-emails")
    @PreAuthorize(MARKERS)
    public ResponseEntity<YuktiSetuResponse<EmailResolution>> resolveEmails(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable Long postingId, @PathVariable Long roundId,
            @RequestBody @Valid ResolveEmailsRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                markingService.resolveEmails(actor, postingId, roundId, request.text())));
    }

    @PostMapping("/{roundId}/publish")
    @PreAuthorize(MANAGERS)
    public ResponseEntity<YuktiSetuResponse<PublishRoundResult>> publish(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable Long postingId, @PathVariable Long roundId,
            @RequestBody @Valid PublishRoundRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                publishingService.publish(actor, postingId, roundId, request), "Round published"));
    }

    @PostMapping("/{roundId}/corrections")
    @PreAuthorize(MANAGERS)
    public ResponseEntity<YuktiSetuResponse<SheetRow>> correct(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable Long postingId, @PathVariable Long roundId,
            @RequestBody @Valid CorrectionRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                publishingService.correct(actor, postingId, roundId, request), "Result corrected"));
    }

    @GetMapping("/{roundId}/history/{applicationId}")
    @PreAuthorize(READERS)
    public ResponseEntity<YuktiSetuResponse<List<HistoryEntry>>> history(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable Long postingId, @PathVariable Long roundId,
            @PathVariable Long applicationId) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                roundService.history(actor, postingId, roundId, applicationId)));
    }

    @PostMapping("/end")
    @PreAuthorize(MANAGERS)
    public ResponseEntity<YuktiSetuResponse<EndDriveResult>> end(
            @AuthenticationPrincipal UserPrincipal actor, @PathVariable Long postingId,
            @RequestBody EndDriveRequest request) {
        return ResponseEntity.ok(YuktiSetuResponse.success(endService.end(actor, postingId, request), "Drive ended"));
    }
}
