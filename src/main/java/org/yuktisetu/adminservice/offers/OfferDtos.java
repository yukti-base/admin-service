package org.yuktisetu.adminservice.offers;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The post-drive results page and the TnP's decisions on offers (2026-11-01). */
public final class OfferDtos {

    private OfferDtos() {}

    public record OfferView(
            Long offerId,
            String status,
            String offerType,
            BigDecimal ctcAmount,
            String ctcCurrency,
            Instant acceptedAt,
            Instant declinedAt,
            String declineReason,
            Instant withdrawnAt,
            String withdrawalReason,
            Instant revokedAt,
            String revokeReason) {}

    public record WithdrawalView(
            Long requestId,
            String status,
            String reason,
            Instant requestedAt,
            Instant decidedAt,
            String decisionNote) {}

    /** One shortlisted student: who they are, how they scored, how far they got, and their offer. */
    public record ResultRow(
            Long applicationId,
            Long studentUserId,
            String name,
            String email,
            String phone,
            String prn,
            String college,
            String department,
            BigDecimal cgpa,
            String applicationStatus,
            String resumeUrl,
            String resumeFileName,
            BigDecimal composite,
            Integer rank,
            Boolean meetsThreshold,
            /** The stored ScoringTrace breakdown, exactly as the shortlist screen shows it. */
            Map<String, Object> breakdown,
            Integer lastRoundSeq,
            String lastRoundName,
            String lastRoundOutcome,
            OfferView offer,
            WithdrawalView withdrawal) {}

    public record DriveResults(
            Long postingId,
            String postingStatus,
            boolean driveEnded,
            int shortlisted,
            int offered,
            int accepted,
            int pendingWithdrawals,
            boolean canDecideWithdrawals,
            boolean canWithdrawForCompany,
            List<ResultRow> rows) {}

    public record WithdrawalDecision(@NotNull Boolean approve, @Size(max = 500) String note) {}

    public record CompanyWithdrawal(@NotBlank @Size(min = 5, max = 500) String reason) {}
}
