package org.yuktisetu.adminservice.dto;

import org.yuktisetu.model.ReapplicationStance;

import java.math.BigDecimal;

public record ReapplicationPolicyResponse(
        Long postingId,
        ReapplicationStance placedStudentStance,
        BigDecimal minPackageUpliftPercent,
        BigDecimal minAbsolutePackage,
        ReapplicationStance interningStudentStance,
        BigDecimal minStipendUpliftPercent,
        ReapplicationStance pendingOfferStance,
        Integer maxLiveOffers,
        boolean countInternshipTowardsPlacement,
        boolean allowIfLastOfferDeclined,
        Integer minDaysSinceLastOffer,
        boolean requireAdminDecisionForPlaced
) {}
