package com.app.promptle.image.generation;

import com.app.promptle.image.api.ImageGenerationService;
import com.app.promptle.image.api.ImageStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
@ConditionalOnProperty(name = "image.generation.provider", havingValue = "comfyui")
public class ComfyUIGenerationService implements ImageGenerationService {

    private static final int MAX_POLL_ATTEMPTS = 30;

    private final RestTemplate restTemplate;
    private final ImageStorageService imageStorageService;
    private final String comfyUiUrl;
    private final long pollIntervalMs;

    @Autowired
    public ComfyUIGenerationService(ImageStorageService imageStorageService,
                                    @Value("${image.generation.comfyui.url:http://localhost:8000}") String comfyUiUrl) {
        this.restTemplate = new RestTemplate();
        this.imageStorageService = imageStorageService;
        this.comfyUiUrl = comfyUiUrl;
        this.pollIntervalMs = 1000L;
    }

    /** Package-private constructor for testing — allows injecting a mock RestTemplate. */
    ComfyUIGenerationService(ImageStorageService imageStorageService, String comfyUiUrl, RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.imageStorageService = imageStorageService;
        this.comfyUiUrl = comfyUiUrl;
        this.pollIntervalMs = 0L;
    }

    @Override
    @Async("promptleTaskExecutor")
    public CompletableFuture<String> generateImage(String prompt) {
        try {
            Map<String, Object> promptResponse = restTemplate.postForObject(
                    comfyUiUrl + "/prompt", Map.of("prompt", prompt), Map.class);
            if (promptResponse == null) {
                CompletableFuture<String> failed = new CompletableFuture<>();
                failed.completeExceptionally(new RuntimeException("No response from ComfyUI"));
                return failed;
            }
            String promptId = (String) promptResponse.get("prompt_id");
            String gameId = UUID.randomUUID().toString();

            for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
                Map<String, Object> history = restTemplate.getForObject(
                        comfyUiUrl + "/history/" + promptId, Map.class);
                if (history != null && history.containsKey(promptId)) {
                    Map<String, Object> entry = (Map<String, Object>) history.get(promptId);
                    Map<String, Object> outputs = (Map<String, Object>) entry.get("outputs");
                    Map<String, Object> node = (Map<String, Object>) outputs.get("9");
                    List<Map<String, Object>> images = (List<Map<String, Object>>) node.get("images");
                    String filename = (String) images.get(0).get("filename");

                    byte[] bytes = restTemplate.getForObject(
                            comfyUiUrl + "/view?filename=" + filename + "&type=output", byte[].class);
                    String url = imageStorageService.store(gameId, promptId, bytes);
                    return CompletableFuture.completedFuture(url);
                }
                if (pollIntervalMs > 0 && attempt < MAX_POLL_ATTEMPTS - 1) {
                    Thread.sleep(pollIntervalMs);
                }
            }
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RuntimeException("ComfyUI timed out after " + MAX_POLL_ATTEMPTS + " poll attempts"));
            return failed;
        } catch (Exception e) {
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }
}
