package org.yuktisetu.adminservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.yuktisetu.core.async.YuktiSetuAsyncConfig;

/**
 * Runs an eligibility resolution on a background thread.
 *
 * A separate bean on purpose, and this is not stylistic. Spring's @Transactional
 * works through a proxy, so a method calling another method on the SAME bean
 * bypasses it entirely: when this lived inside EligibilityResolutionService, the
 * @Async wrapper invoked this.execute(...) directly and the resolution ran with no
 * transaction at all. It only looked fine because the first test cohort was empty
 * and returned before the first flush -- the moment a real candidate appeared it
 * failed with "No EntityManager with actual transaction available".
 *
 * Calling across bean boundaries means both execute() and markFailed() get their
 * proxies, so the work runs in a transaction and a failure is still recorded in
 * its own.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EligibilityRunLauncher {

    private final EligibilityResolutionService resolutionService;

    /**
     * Never throws: there is no caller left to receive it. Every failure lands on
     * the run row so the TnP sees a FAILED run with a reason, instead of a
     * resolution that silently never happened -- which on a published drive means
     * nobody becomes eligible and nothing says why.
     */
    @Async(YuktiSetuAsyncConfig.HEAVY_EXECUTOR)
    public void launch(Long runId, Long postingId, Long actorUserId) {
        try {
            resolutionService.execute(runId, postingId, actorUserId);
        } catch (Exception e) {
            log.error("Eligibility run {} failed for postingId={}: {}", runId, postingId, e.getMessage(), e);
            resolutionService.markFailed(runId, e);
        }
    }
}
