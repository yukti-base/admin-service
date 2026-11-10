package org.yuktisetu.adminservice.offers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.adminservice.exception.AdminExceptions;
import org.yuktisetu.adminservice.rounds.RoundNotifier;
import org.yuktisetu.adminservice.rounds.RoundSupport;
import org.yuktisetu.core.exception.ApiException;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.drive.db.OfferWithdrawalRequest;
import org.yuktisetu.drive.db.PlacementOffer;
import org.yuktisetu.drive.model.ActorKind;
import org.yuktisetu.drive.model.ApplicationStatus;
import org.yuktisetu.drive.model.ApplicationStatusTransitions;
import org.yuktisetu.drive.model.OfferStatus;
import org.yuktisetu.drive.model.OfferStatusTransitions;
import org.yuktisetu.drive.model.WithdrawalRequestStatus;
import org.yuktisetu.drive.repository.JobApplicationRepository;
import org.yuktisetu.drive.repository.OfferWithdrawalRequestRepository;
import org.yuktisetu.drive.repository.PlacementOfferRepository;

import java.time.Instant;
import java.util.List;

/**
 * The TnP's side of an offer after it is made (decided 2026-11-01).
 *
 *  - A student's withdrawal request is approved or refused by placement staff who
 *    can run the drive. Approval moves the offer ACCEPTED -> WITHDRAWN and the
 *    application OFFER_ACCEPTED -> OFFER_DECLINED: the student no longer counts
 *    as placed through it.
 *  - A company withdrawing an offer comes to the TnP, and only an IT admin
 *    records it: OFFERED or ACCEPTED -> REVOKED, the application -> REJECTED, and
 *    any pending student request is closed.
 *
 * Lock order is the offer row, then the request row, the same order the
 * student's request takes in drive-service, so the two can never deadlock. Each
 * step re-reads state after the lock, so a decision racing a company withdrawal,
 * or two staff deciding at once, gives one outcome and a clear conflict.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfferAdminService {

    private final PlacementOfferRepository offerRepository;
    private final OfferWithdrawalRequestRepository withdrawalRepository;
    private final JobApplicationRepository applicationRepository;
    private final RoundSupport support;
    private final RoundNotifier notifier;

    @Transactional
    public void decideWithdrawal(UserPrincipal actor, Long requestId, boolean approve, String note) {
        try {
            OfferWithdrawalRequest peek = withdrawalRepository.findById(requestId)
                    .orElseThrow(() -> new AdminExceptions.NotFoundException("OfferWithdrawalRequest", requestId));
            support.authorise(actor, peek.getPostingId(), RoundSupport.Access.MANAGE);

            PlacementOffer offer = offerRepository.lockById(peek.getOfferId())
                    .orElseThrow(() -> new AdminExceptions.NotFoundException("PlacementOffer", peek.getOfferId()));
            OfferWithdrawalRequest request = withdrawalRepository.lockById(requestId).orElseThrow();
            if (request.getStatus() != WithdrawalRequestStatus.PENDING) {
                throw new AdminExceptions.RoundStateException("WITHDRAWAL_DECIDED",
                        "This request was already " + request.getStatus().name().toLowerCase() + ".");
            }
            Instant now = Instant.now();
            String cleanNote = note == null || note.isBlank() ? null : note.trim();

            if (approve) {
                if (offer.getStatus() != OfferStatus.ACCEPTED) {
                    throw new AdminExceptions.RoundStateException("OFFER_CHANGED",
                            "The offer is no longer accepted (it is " + offer.getStatus().name().toLowerCase()
                                    + "), so there is nothing to withdraw.");
                }
                move(offer, OfferStatus.WITHDRAWN);
                offer.setWithdrawnAt(now);
                offer.setWithdrawalReason(request.getReason());
                offer.setUpdatedBy(actor.userId());
                offerRepository.save(offer);
                moveApplication(offer, ApplicationStatus.OFFER_ACCEPTED, ApplicationStatus.OFFER_DECLINED, actor,
                        "Offer withdrawn at the student's request: " + request.getReason(), now);
            }
            request.setStatus(approve ? WithdrawalRequestStatus.APPROVED : WithdrawalRequestStatus.REJECTED);
            request.setPendingLock(null);
            request.setDecidedBy(actor.userId());
            request.setDecidedAt(now);
            request.setDecisionNote(cleanNote);
            withdrawalRepository.save(request);

            notifier.offerWithdrawalDecided(request.getPostingId(), request.getStudentUserId(), approve, cleanNote);
            log.info("Withdrawal request {} {} by actorId={} offerId={} note={}", requestId,
                    approve ? "APPROVED" : "REJECTED", actor.userId(), offer.getId(), cleanNote);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Deciding withdrawal request {} failed for actorId={}: {}", requestId, actor.userId(),
                    e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not record the decision. Please try again.");
        }
    }

    /** IT admin only (enforced on the endpoint and here). */
    @Transactional
    public void withdrawForCompany(UserPrincipal actor, Long offerId, String reason) {
        try {
            if (!actor.hasRole("IT_ADMIN")) {
                throw new AdminExceptions.InsufficientAuthorityException();
            }
            String why = reason == null ? "" : reason.trim();
            if (why.length() < 5) {
                throw new AdminExceptions.InvalidRequestException("Give the company's reason for withdrawing the offer.");
            }
            PlacementOffer offer = offerRepository.lockById(offerId)
                    .orElseThrow(() -> new AdminExceptions.NotFoundException("PlacementOffer", offerId));
            OfferStatus was = offer.getStatus();
            if (was != OfferStatus.OFFERED && was != OfferStatus.ACCEPTED) {
                throw new AdminExceptions.RoundStateException("OFFER_NOT_LIVE",
                        "This offer is " + was.name().toLowerCase() + "; only an open or accepted offer can be withdrawn.");
            }
            Instant now = Instant.now();
            move(offer, OfferStatus.REVOKED);
            offer.setRevokedAt(now);
            offer.setRevokedBy(actor.userId());
            offer.setRevokeReason(why);
            offer.setUpdatedBy(actor.userId());
            offerRepository.save(offer);

            ApplicationStatus from = was == OfferStatus.ACCEPTED ? ApplicationStatus.OFFER_ACCEPTED : ApplicationStatus.OFFERED;
            moveApplication(offer, from, ApplicationStatus.REJECTED, actor, "Offer withdrawn by the company: " + why, now);

            // A request the student filed is moot now; close it so it leaves the queue.
            withdrawalRepository.findByOfferIdAndStatus(offerId, WithdrawalRequestStatus.PENDING).ifPresent(r -> {
                r.setStatus(WithdrawalRequestStatus.REJECTED);
                r.setPendingLock(null);
                r.setDecidedBy(actor.userId());
                r.setDecidedAt(now);
                r.setDecisionNote("Closed: the company withdrew the offer.");
                withdrawalRepository.save(r);
            });

            notifier.offerWithdrawnByCompany(offer.getPosting().getId(), offer.getStudentUserId(), why);
            log.warn("Offer {} withdrawn for the company by IT admin actorId={} (was {}): {}", offerId,
                    actor.userId(), was, why);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Company withdrawal of offerId={} by actorId={} failed: {}", offerId, actor.userId(),
                    e.getMessage(), e);
            throw new AdminExceptions.InvalidRequestException("Could not withdraw the offer. Please try again.");
        }
    }

    private static void move(PlacementOffer offer, OfferStatus to) {
        if (!OfferStatusTransitions.canTransition(offer.getStatus(), to)) {
            throw new AdminExceptions.RoundStateException("OFFER_NOT_LIVE",
                    "The offer is " + offer.getStatus().name().toLowerCase() + " and cannot become "
                            + to.name().toLowerCase() + ".");
        }
        offer.setStatus(to);
        offer.setActiveInternshipLock(OfferStatusTransitions.internshipLockValue(
                offer.getOfferType(), to, offer.getStudentUserId()));
    }

    /** The application follows the offer; one not where we expect is left alone and logged. */
    private void moveApplication(PlacementOffer offer, ApplicationStatus from, ApplicationStatus to,
                                 UserPrincipal actor, String reason, Instant now) {
        if (offer.getApplication() == null || !ApplicationStatusTransitions.canTransition(from, to)) {
            return;
        }
        Long appId = offer.getApplication().getId();
        String why = reason.length() <= 255 ? reason : reason.substring(0, 252) + "...";
        applicationRepository.recordHistoryForIds(List.of(appId), from.name(), to.name(), now, actor.userId(),
                ActorKind.TNP.name(), why);
        int moved = applicationRepository.updateStatusForIds(List.of(appId), from, to, actor.userId());
        if (moved != 1) {
            // History was written for a move that did not happen: roll the whole step back.
            throw new AdminExceptions.RoundStateException("APPLICATION_MOVED",
                    "The student's application is no longer " + from.name().toLowerCase().replace('_', ' ')
                            + ". Reload and try again.");
        }
    }
}
