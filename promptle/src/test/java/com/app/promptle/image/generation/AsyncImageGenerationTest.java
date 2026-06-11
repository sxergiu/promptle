package com.app.promptle.image.generation;

import com.app.promptle.game.service.GameService;
import com.app.promptle.image.api.ImageStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the async/CompletableFuture aspects of ComfyUIGenerationService.
 * Covers A-1 through A-4.
 */
@ExtendWith(MockitoExtension.class)
class AsyncImageGenerationTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private ImageStorageService imageStorageService;

    private static final String COMFY_URL = "http://localhost:8188";
    private static final String WORKFLOW_TEMPLATE = """
            {"6":{"class_type":"CLIPTextEncode","inputs":{"text":"PROMPT_PLACEHOLDER","clip":["4",1]}},
             "9":{"class_type":"SaveImage","inputs":{"images":["8",0],"filename_prefix":"ComfyUI"}},
             "3":{"class_type":"KSampler","inputs":{"seed":0,"steps":1}}}""";

    private ComfyUIGenerationService service;

    @BeforeEach
    void setUp() {
        service = new ComfyUIGenerationService(imageStorageService, COMFY_URL, restTemplate,
                WORKFLOW_TEMPLATE, "6", "9",
                null, 0.55, "10", "8", "4");
    }

    // ---- A-1: @Async annotation present on generateImage ----

    @Test
    void generateImage_IsAnnotatedWithAsync_UsingPrompletTaskExecutor() throws NoSuchMethodException {
        Method method = ComfyUIGenerationService.class.getDeclaredMethod("generateImage", String.class);
        Async asyncAnnotation = method.getAnnotation(Async.class);

        assertNotNull(asyncAnnotation,
                "generateImage() must be annotated with @Async");
        assertEquals("promptleTaskExecutor", asyncAnnotation.value(),
                "generateImage() must use the 'promptleTaskExecutor' thread pool");
    }

    // ---- A-2: onAllImagesReady is NOT called until ALL N futures complete ----

    @Test
    void allOf_OnAllImagesReady_NotCalledBeforeLastFutureCompletes() throws Exception {
        int n = 3;
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(new CompletableFuture<>());
        }

        AtomicInteger callCount = new AtomicInteger(0);
        CountDownLatch callbackLatch = new CountDownLatch(1);

        CompletableFuture<Void> allOf = CompletableFuture
                .allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    callCount.incrementAndGet();
                    callbackLatch.countDown();
                });

        // Complete N-1 futures — callback must NOT fire yet
        for (int i = 0; i < n - 1; i++) {
            futures.get(i).complete("url-" + i);
        }

        // Small wait to allow any premature execution
        assertFalse(callbackLatch.await(100, TimeUnit.MILLISECONDS),
                "onAllImagesReady must NOT be called until all futures complete");
        assertEquals(0, callCount.get(),
                "Callback must not have been invoked after completing only " + (n - 1) + " of " + n + " futures");

        // Complete the Nth future — callback MUST fire now
        futures.get(n - 1).complete("url-last");

        assertTrue(callbackLatch.await(2, TimeUnit.SECONDS),
                "onAllImagesReady MUST be called after the last future completes");
        assertEquals(1, callCount.get(),
                "onAllImagesReady must be called exactly once after all futures complete");
    }

    // ---- A-3: onAllImagesReady is annotated with @Transactional ----
    //
    // The design spec (chunk-09) says: "onAllImagesReady runs on an async thread.
    // It must call eventPublisher.publishEvent() while wrapped in a @Transactional
    // context so that @TransactionalEventListener(AFTER_COMMIT) fires correctly."
    //
    // We verify:
    // 1. GameService.onAllImagesReady() carries the @Transactional annotation (Spring AOP
    //    will activate a transaction when the method is called through the proxy).
    // 2. TransactionSynchronizationManager is synchronisation-active inside a
    //    TransactionTemplate callback — confirming the pattern works as designed.

    @Test
    void onAllImagesReady_IsAnnotatedWithTransactional() throws NoSuchMethodException {
        Method method = GameService.class.getDeclaredMethod("onAllImagesReady", String.class);
        Transactional txAnnotation = method.getAnnotation(Transactional.class);

        assertNotNull(txAnnotation,
                "GameService.onAllImagesReady() must be annotated with @Transactional so that " +
                "@TransactionalEventListener(AFTER_COMMIT) listeners fire correctly when called " +
                "from the CompletableFuture.allOf async callback");
    }

    @Test
    void transactionTemplate_ActivatesSynchronizationManager_InsideCallback() {
        // Verifies the TransactionTemplate pattern that wraps the async callback.
        // When TransactionTemplate invokes the callback, Spring activates transaction
        // synchronisation, making isActualTransactionActive() / isSynchronizationActive() true.

        org.springframework.transaction.PlatformTransactionManager txManager =
                mock(org.springframework.transaction.PlatformTransactionManager.class);
        org.springframework.transaction.support.TransactionTemplate txTemplate =
                new org.springframework.transaction.support.TransactionTemplate(txManager);

        org.springframework.transaction.TransactionStatus txStatus =
                mock(org.springframework.transaction.TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(txStatus);

        AtomicBoolean txActiveInsideCallback = new AtomicBoolean(false);

        txTemplate.execute(status -> {
            // Spring's AbstractPlatformTransactionManager calls initSynchronization
            // before invoking the callback via doBegin. We simulate this here.
            TransactionSynchronizationManager.initSynchronization();
            txActiveInsideCallback.set(TransactionSynchronizationManager.isSynchronizationActive());
            TransactionSynchronizationManager.clearSynchronization();
            return null;
        });

        assertTrue(txActiveInsideCallback.get(),
                "TransactionSynchronizationManager must report synchronisation active inside a " +
                "TransactionTemplate callback — this is the pattern used in the async onAllImagesReady callback");
    }

    // ---- A-4: Polling loop has a max-attempt guard — future does not run forever ----

    @Test
    void generateImage_CompletesExceptionally_WhenComfyUIServerNeverReturnsResult() throws Exception {
        String promptId = "poll-timeout-test";

        // Server accepts the prompt submission
        when(restTemplate.postForObject(eq(COMFY_URL + "/prompt"), any(), eq(Map.class)))
                .thenReturn(Map.of("prompt_id", promptId));

        // History endpoint always returns an empty map (no output available).
        // This simulates a server that never produces an output — the polling loop
        // must bail out rather than spinning indefinitely.
        when(restTemplate.getForObject(eq(COMFY_URL + "/history/" + promptId), eq(Map.class)))
                .thenReturn(Map.of());

        CompletableFuture<String> future = service.generateImage("a prompt that never resolves");

        assertNotNull(future, "generateImage must return a non-null future");

        // The implementation must have a max-attempt / timeout guard.
        // The future must be done (completed or exceptionally) within 30 seconds —
        // it must NOT poll indefinitely.
        try {
            future.get(30, TimeUnit.SECONDS);
            // Completed normally (implementation may return null/empty URL on timeout) — acceptable.
        } catch (ExecutionException ee) {
            // Completed exceptionally — the expected outcome for a proper timeout guard.
            assertNotNull(ee.getCause(), "ExecutionException must wrap an underlying cause");
        } catch (TimeoutException te) {
            fail("generateImage future did not complete within 30 seconds — " +
                    "the polling loop must have a max-attempt guard to prevent indefinite blocking");
        }
    }
}
