package com.app.promptle.image.generation;

import com.app.promptle.image.api.ImageStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ComfyUIGenerationServiceTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private ImageStorageService imageStorageService;

    private ComfyUIGenerationService service;

    private static final String COMFY_URL = "http://localhost:8000";

    @BeforeEach
    void setUp() {
        service = new ComfyUIGenerationService(imageStorageService, COMFY_URL, restTemplate);
    }

    @Test
    void generateImage_PostsToComfyUiAndReturnsFuture() throws Exception {
        String promptId = "prompt-123";
        Map<String, Object> promptResponse = Map.of("prompt_id", promptId);

        when(restTemplate.postForObject(eq(COMFY_URL + "/prompt"), any(), eq(Map.class)))
                .thenReturn(promptResponse);

        // History indicates completed
        Map<String, Object> outputNode = Map.of(
                "images", java.util.List.of(Map.of("filename", "output.png", "type", "output"))
        );
        Map<String, Object> historyEntry = Map.of(
                "outputs", Map.of("9", outputNode)
        );
        Map<String, Object> history = Map.of(promptId, historyEntry);

        when(restTemplate.getForObject(eq(COMFY_URL + "/history/" + promptId), eq(Map.class)))
                .thenReturn(history);

        when(restTemplate.getForObject(contains(COMFY_URL + "/view"), eq(byte[].class)))
                .thenReturn(new byte[]{1, 2, 3});

        when(imageStorageService.store(anyString(), anyString(), any(byte[].class)))
                .thenReturn("/api/images/test-game-id/prompt-123");

        CompletableFuture<String> future = service.generateImage("A sunset over the ocean");

        assertNotNull(future);
        String result = future.get();
        assertEquals("/api/images/test-game-id/prompt-123", result);
    }

    @Test
    void generateImage_StoresImageAndReturnsUrl() throws Exception {
        String promptId = "prompt-456";
        Map<String, Object> promptResponse = Map.of("prompt_id", promptId);

        when(restTemplate.postForObject(eq(COMFY_URL + "/prompt"), any(), eq(Map.class)))
                .thenReturn(promptResponse);

        Map<String, Object> outputNode = Map.of(
                "images", java.util.List.of(Map.of("filename", "img.png", "type", "output"))
        );
        Map<String, Object> history = Map.of(promptId,
                Map.of("outputs", Map.of("9", outputNode)));

        when(restTemplate.getForObject(eq(COMFY_URL + "/history/" + promptId), eq(Map.class)))
                .thenReturn(history);

        byte[] imageBytes = new byte[]{10, 20, 30};
        when(restTemplate.getForObject(contains(COMFY_URL + "/view"), eq(byte[].class)))
                .thenReturn(imageBytes);

        when(imageStorageService.store(anyString(), anyString(), eq(imageBytes)))
                .thenReturn("/api/images/test/img");

        CompletableFuture<String> future = service.generateImage("mountains");
        future.get();

        verify(imageStorageService).store(anyString(), anyString(), eq(imageBytes));
    }

    @Test
    void generateImage_NetworkFailure_FutureCompletesExceptionally() {
        when(restTemplate.postForObject(eq(COMFY_URL + "/prompt"), any(), eq(Map.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        CompletableFuture<String> future = service.generateImage("a prompt");

        assertNotNull(future);
        // N-6: Use a specific, deterministic assertion rather than a disjunction.
        // The future must be completed exceptionally (not pending, not normally completed).
        assertTrue(future.isCompletedExceptionally(),
                "Future must complete exceptionally on network failure");
        // Additionally verify the cause is accessible via get()
        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(),
                "future.get() must throw ExecutionException wrapping the original cause");
        assertNotNull(ex.getCause(), "ExecutionException must have a non-null cause");
    }
}
