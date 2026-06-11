package com.app.promptle.image.generation;

import com.app.promptle.image.api.ImageStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    private static final String COMFY_URL = "http://localhost:8188";
    private static final String WORKFLOW_TEMPLATE = """
            {"6":{"class_type":"CLIPTextEncode","inputs":{"text":"PROMPT_PLACEHOLDER","clip":["4",1]}},
             "9":{"class_type":"SaveImage","inputs":{"images":["8",0],"filename_prefix":"ComfyUI"}},
             "3":{"class_type":"KSampler","inputs":{"seed":0,"steps":1}}}""";
    private static final String IMG2IMG_WORKFLOW_TEMPLATE = """
            {"8":{"class_type":"CLIPTextEncode","inputs":{"text":"PROMPT_PLACEHOLDER","clip":["9",1]}},
             "4":{"class_type":"SaveImage","inputs":{"images":["6",0],"filename_prefix":"ComfyUI"}},
             "10":{"class_type":"LoadImage","inputs":{"image":"placeholder.png"}},
             "11":{"class_type":"VAEEncode","inputs":{"pixels":["10",0],"vae":["9",2]}},
             "2":{"class_type":"KSampler","inputs":{"seed":0,"steps":1,"denoise":0.55,"latent_image":["11",0]}}}""";

    @BeforeEach
    void setUp() {
        service = new ComfyUIGenerationService(imageStorageService, COMFY_URL, restTemplate,
                WORKFLOW_TEMPLATE, "6", "9",
                IMG2IMG_WORKFLOW_TEMPLATE, 0.55, "10", "8", "4");
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

    // ---- img2img tests ----

    @Test
    void generateImageFromImage_UploadsImageAndSubmitsImg2imgWorkflow() throws Exception {
        // Mock upload response
        Map<String, Object> uploadResponse = Map.of("name", "prev_uploaded.png", "subfolder", "", "type", "input");
        when(restTemplate.postForObject(eq(COMFY_URL + "/upload/image"), any(), eq(Map.class)))
                .thenReturn(uploadResponse);

        // Mock prompt submission
        String promptId = "img2img-prompt-123";
        when(restTemplate.postForObject(eq(COMFY_URL + "/prompt"), any(), eq(Map.class)))
                .thenReturn(Map.of("prompt_id", promptId));

        // Mock history (use output node "4" for img2img)
        Map<String, Object> outputNode = Map.of(
                "images", java.util.List.of(Map.of("filename", "result.png", "type", "output"))
        );
        when(restTemplate.getForObject(eq(COMFY_URL + "/history/" + promptId), eq(Map.class)))
                .thenReturn(Map.of(promptId, Map.of("outputs", Map.of("4", outputNode))));

        when(restTemplate.getForObject(contains(COMFY_URL + "/view"), eq(byte[].class)))
                .thenReturn(new byte[]{1, 2, 3});
        when(imageStorageService.store(anyString(), anyString(), any(byte[].class)))
                .thenReturn("/api/images/test/img2img-result");

        CompletableFuture<String> future = service.generateImageFromImage("a cat", new byte[]{10, 20});
        String result = future.get();

        assertEquals("/api/images/test/img2img-result", result);
        // Verify upload was called
        verify(restTemplate).postForObject(eq(COMFY_URL + "/upload/image"), any(), eq(Map.class));
    }

    @Test
    void generateImageFromImage_FallsBackToTxt2img_WhenWorkflowNotConfigured() throws Exception {
        // Create service with null img2img workflow
        ComfyUIGenerationService serviceNoImg2img = new ComfyUIGenerationService(
                imageStorageService, COMFY_URL, restTemplate,
                WORKFLOW_TEMPLATE, "6", "9",
                null, 0.55, "10", "8", "4");

        // Mock txt2img flow (same as existing test)
        String promptId = "fallback-123";
        when(restTemplate.postForObject(eq(COMFY_URL + "/prompt"), any(), eq(Map.class)))
                .thenReturn(Map.of("prompt_id", promptId));
        Map<String, Object> outputNode = Map.of(
                "images", java.util.List.of(Map.of("filename", "output.png", "type", "output"))
        );
        when(restTemplate.getForObject(eq(COMFY_URL + "/history/" + promptId), eq(Map.class)))
                .thenReturn(Map.of(promptId, Map.of("outputs", Map.of("9", outputNode))));
        when(restTemplate.getForObject(contains(COMFY_URL + "/view"), eq(byte[].class)))
                .thenReturn(new byte[]{1, 2, 3});
        when(imageStorageService.store(anyString(), anyString(), any(byte[].class)))
                .thenReturn("/api/images/test/fallback");

        CompletableFuture<String> future = serviceNoImg2img.generateImageFromImage("test", new byte[]{1});
        String result = future.get();

        assertEquals("/api/images/test/fallback", result);
        // Should NOT upload — went straight to txt2img
        verify(restTemplate, never()).postForObject(eq(COMFY_URL + "/upload/image"), any(), eq(Map.class));
    }

    @Test
    void generateImageFromImage_UploadFailure_CompletesExceptionally() {
        when(restTemplate.postForObject(eq(COMFY_URL + "/upload/image"), any(), eq(Map.class)))
                .thenThrow(new RuntimeException("Upload failed"));

        CompletableFuture<String> future = service.generateImageFromImage("test", new byte[]{1});

        assertTrue(future.isCompletedExceptionally());
    }
}
