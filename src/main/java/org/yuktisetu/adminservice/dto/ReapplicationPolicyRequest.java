package org.yuktisetu.adminservice.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.yuktisetu.drive.model.ReapplicationStance;

import java.math.BigDecimal;

/**
 * Who may sit for this drive given what they already hold. Set per drive, never
 * hard-coded, because TnP placement policy changes year to year.
 *
 * The two halves are independent. There is no one-offer policy: a placed student
 * may sit again, typically above a package uplift, while an interning student is
 * normally locked out of another internship unless this is a conversion drive.
 */
public record ReapplicationPolicyRequest(
        @NotNull ReapplicationStance placedStudentStance,
        @Min(0) BigDecimal minPackageUpliftPercent,
        @Min(0) BigDecimal minAbsolutePackage,

        @NotNull ReapplicationStance interningStudentStance,
        @Min(0) BigDecimal minStipendUpliftPercent,

        ReapplicationStance pendingOfferStance,
        @Min(1) Integer maxLiveOffers,

        /** Does a PPO count as being placed for this drive's purposes? */
        Boolean countInternshipTowardsPlacement,
        Boolean allowIfLastOfferDeclined,
        @Min(0) Integer minDaysSinceLastOffer,

        /**
         * When true, an already-placed student is not admitted on the uplift rule
         * alone -- the TnP must rule on them individually for this drive.
         */
        Boolean requireAdminDecisionForPlaced
) {}
