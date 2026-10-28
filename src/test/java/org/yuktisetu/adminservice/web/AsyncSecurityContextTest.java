package org.yuktisetu.adminservice.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.yuktisetu.core.async.YuktiSetuAsyncConfig;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How the async pools carry the caller's identity onto a worker thread.
 *
 * The decorator used to hand the worker the request thread's own SecurityContext
 * object and then clear the context in its finally block. Under CallerRunsPolicy
 * (a full queue) the task runs ON the submitting thread, so that finally wiped the
 * authentication of the request still in progress.
 */
class AsyncSecurityContextTest {

    private final YuktiSetuAsyncConfig config = new YuktiSetuAsyncConfig();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static Authentication user(String name) {
        return new UsernamePasswordAuthenticationToken(name, "n/a", List.of());
    }

    private TaskDecorator decorator() throws Exception {
        Method m = YuktiSetuAsyncConfig.class.getDeclaredMethod("securityContextPropagatingDecorator");
        m.setAccessible(true);
        return (TaskDecorator) m.invoke(config);
    }

    @Test
    @DisplayName("a worker sees the submitting user, through a COPY of the context rather than the same object")
    void workerGetsACopyOfTheCallersIdentity() throws Exception {
        Authentication alice = user("alice");
        SecurityContext callers = SecurityContextHolder.createEmptyContext();
        callers.setAuthentication(alice);
        SecurityContextHolder.setContext(callers);

        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) config.yuktiSetuTaskExecutor();
        try {
            AtomicReference<SecurityContext> seenContext = new AtomicReference<>();
            Callable<Authentication> task = () -> {
                seenContext.set(SecurityContextHolder.getContext());
                return SecurityContextHolder.getContext().getAuthentication();
            };
            Authentication onWorker = pool.submit(task).get();

            assertSame(alice, onWorker, "the worker acts as the submitting user");
            assertNotSame(callers, seenContext.get(), "the worker must not share the request thread's context object");

            // Clearing the request's context afterwards cannot reach a task that already holds its copy.
            SecurityContextHolder.clearContext();
            assertNotNull(seenContext.get().getAuthentication());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("a task that runs on the SUBMITTING thread (queue full) does not log the request out")
    void callerRunsDoesNotWipeTheRequestsContext() throws Exception {
        Authentication alice = user("alice");
        SecurityContext callers = SecurityContextHolder.createEmptyContext();
        callers.setAuthentication(alice);
        SecurityContextHolder.setContext(callers);

        Runnable decorated = decorator().decorate(() -> { });
        decorated.run();                      // exactly what CallerRunsPolicy does: same thread

        assertSame(alice, SecurityContextHolder.getContext().getAuthentication(),
                "the request must still be authenticated after its own rejected task ran inline");
    }

    @Test
    @DisplayName("a pooled thread carries nothing over from one task to the next")
    void noIdentityLeaksBetweenTasks() throws Exception {
        Authentication alice = user("alice");
        SecurityContext callers = SecurityContextHolder.createEmptyContext();
        callers.setAuthentication(alice);
        SecurityContextHolder.setContext(callers);
        Runnable decorated = decorator().decorate(() -> { });
        SecurityContextHolder.clearContext();

        AtomicReference<Authentication> after = new AtomicReference<>(alice);
        Thread worker = new Thread(() -> {
            decorated.run();
            after.set(SecurityContextHolder.getContext().getAuthentication());
        });
        worker.start();
        worker.join();

        assertNull(after.get(), "a worker that had no identity before the task has none after it");
    }
}
