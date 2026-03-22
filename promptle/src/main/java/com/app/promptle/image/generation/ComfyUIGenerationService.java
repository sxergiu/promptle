package com.app.promptle.image.generation;

import com.app.promptle.image.api.ImageGenerationService;
import com.app.promptle.image.api.ImageStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Generates images via ComfyUI.
 * Active when image.generation.provider=comfyui.
 * Fully implemented in Chunk 9.
 */
@Service
@ConditionalOnProperty(name = "image.generation.provider", havingValue = "comfyui")
public class ComfyUIGenerationService implements ImageGenerationService {

    private final RestTemplate restTemplate;
    private final ImageStorageService imageStorageService;
    private final String comfyUiUrl;

    public ComfyUIGenerationService(ImageStorageService imageStorageService,
                                    @Value("${image.generation.comfyui.url:http://localhost:8000}") String comfyUiUrl) {
        this.restTemplate = new RestTemplate();
        this.imageStorageService = imageStorageService;
        this.comfyUiUrl = comfyUiUrl;
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

            Map<String, Object> history = restTemplate.getForObject(
                    comfyUiUrl + "/history/" + promptId, Map.class);
            Map<String, Object> entry = (Map<String, Object>) history.get(promptId);
            Map<String, Object> outputs = (Map<String, Object>) entry.get("outputs");
            Map<String, Object> node = (Map<String, Object>) outputs.get("9");
            List<Map<String, Object>> images = (List<Map<String, Object>>) node.get("images");
            String filename = (String) images.get(0).get("filename");

            byte[] bytes = restTemplate.getForObject(
                    comfyUiUrl + "/view?filename=" + filename + "&type=output", byte[].class);
            String url = imageStorageService.store(gameId, promptId, bytes);
            return CompletableFuture.completedFuture(url);
        } catch (Exception e) {
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }
}
