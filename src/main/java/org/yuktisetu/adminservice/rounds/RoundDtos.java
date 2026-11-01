package org.yuktisetu.adminservice.rounds;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.yuktisetu.drive.model.OfferType;
import org.yuktisetu.drive.model.RoundMarkSource;
import org.yuktisetu.drive.model.RoundOutcome;
import org.yuktisetu.drive.model.RoundType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Requests and responses for a drive's rounds. Booleans are boxed: an omitted flag means "off". */
public final class RoundDtos {

    private RoundDtos() {}

    // ------------------------------------------------------------- definition

    /**
     * Adding or editing a round. On an edit, null leaves a field alone and an
     * empty string clears an optional one (PATCH semantics); expectedVersion is
     * the round's version the editor loaded, so two coordinators editing at once
     * cannot overwrite each other.
     */
    public record RoundRequest(
            @Size(max = 120) String name,
            RoundType roundType,
            Instant scheduledAt,
            Boolean clearSchedule,
            @Size(max = 255) String venue,
            @Size(max = 4000) String instructions,
            Long expectedVersion,
            Boolean notifyCandidates) {}

    public record Counts(int advanced, int notAdvanced, int absent, int unmarked) {}

    public record RoundView(
            Long id,
            int seq,
            String name,
            RoundType roundType,
            String roundTypeLabel,
            Instant scheduledAt,
            String venue,
            String instructions,
            String status,
            boolean current,
            boolean finalRound,
            boolean last,
            Instant publishedAt,
            String publishedByName,
            long marksVersion,
            long version,
            Counts counts) {}

    /** Everything the Rounds tab needs to decide what to show and what to allow. */
    public record RoundsOverview(
            Long postingId,
            String postingStatus,
            String shortlistingMode,
            boolean shortlistPublished,
            boolean driveEnded,
            String jobType,
            OfferType defaultOfferType,
            List<OfferType> offerTypes,
            boolean canManage,
            boolean canMark,
            int stillInProcess,
            int offered,
            Long currentRoundId,
            List<RoundView> rounds) {}

    // ------------------------------------------------------------------ sheet

    public record SheetRow(
            Long applicationId,
            Long studentUserId,
            String name,
            String email,
            String prn,
            String college,
            String department,
            RoundOutcome outcome,
            Integer version,
            String source,
            String markedByName,
            Instant markedAt,
            String applicationStatus) {}

    /**
     * One round's candidates and marks. unchanged=true (rows empty) answers a
     * poll whose knownVersion still matches: nothing moved since the last load.
     */
    public record RoundSheet(
            Long roundId,
            String status,
            boolean current,
            long marksVersion,
            boolean unchanged,
            Counts counts,
            List<SheetRow> rows) {}

    // ------------------------------------------------------------------ marks

    /**
     * outcome null clears the mark. expectedVersion is the version the marker
     * saw: null for "unmarked when I looked".
     */
    public record MarkItem(@NotNull Long applicationId, RoundOutcome outcome, Integer expectedVersion) {}

    public record MarkRequest(@NotEmpty @Size(max = 5000) List<@Valid MarkItem> items, RoundMarkSource source) {}

    /**
     * A mark that did not apply, and why: CHANGED (someone else marked this
     * student since you looked -- here is what they set) or NOT_A_CANDIDATE (the
     * student is no longer in this round: disqualified, or out of the drive).
     */
    public record MarkConflict(
            Long applicationId,
            String name,
            String reason,
            RoundOutcome currentOutcome,
            Integer currentVersion,
            String markedByName,
            Instant markedAt,
            String applicationStatus) {}

    public record MarkResult(int applied, int unchanged, List<MarkConflict> conflicts, long marksVersion) {}

    // ------------------------------------------------------------- email list

    public record ResolveEmailsRequest(@NotNull @Size(max = 1_000_000) String text) {}

    public record MatchedStudent(Long applicationId, String email, String name, RoundOutcome currentOutcome,
                                 Integer version) {}

    public record NotInRound(String email, String name, String applicationStatus) {}

    /**
     * What a pasted or uploaded list resolved to. Nothing is marked by resolving;
     * the portal shows this, and the marks follow as an ordinary MarkRequest
     * carrying the versions read here.
     */
    public record EmailResolution(
            int listed,
            List<MatchedStudent> matched,
            List<String> noAccount,
            List<String> notApplied,
            List<NotInRound> notInRound,
            Map<String, Integer> duplicates,
            List<String> unreadable) {}

    // ---------------------------------------------------------------- publish

    /**
     * marksVersion is the one the coordinator reviewed. finalRound must be true
     * exactly when this is the drive's last round -- a deliberate, explicit
     * confirmation, because publishing the final round marks every advancing
     * student OFFERED and congratulates them.
     */
    public record PublishRoundRequest(
            @NotNull Long marksVersion,
            Boolean finalRound,
            Boolean confirmUnmarked,
            OfferType offerType) {}

    public record PublishRoundResult(
            RoundView round,
            int advanced,
            int notAdvanced,
            int absent,
            int unmarkedCountedNotAdvanced,
            int offered,
            int droppedNonCandidateMarks) {}

    // ------------------------------------------------------------- correction

    public record CorrectionRequest(
            @NotNull Long applicationId,
            @NotNull RoundOutcome outcome,
            @NotNull @Size(min = 5, max = 1000) String reason,
            @NotNull Integer expectedVersion) {}

    public record HistoryEntry(
            String fromOutcome,
            String toOutcome,
            String kind,
            String source,
            String reason,
            String changedByName,
            Instant changedAt) {}

    // -------------------------------------------------------------- end drive

    public record EndDriveRequest(Boolean confirmUnfinished) {}

    public record EndDriveResult(
            int offered,
            int endedInProcess,
            int departmentsReported,
            int coordinatorsNotified,
            List<String> departmentsWithoutCoordinator) {}
}
