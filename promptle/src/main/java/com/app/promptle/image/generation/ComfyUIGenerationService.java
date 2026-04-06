package com.app.promptle.image.generation;

import com.app.promptle.image.api.ImageGenerationService;
import com.app.promptle.image.api.ImageStorageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

@Service
@ConditionalOnProperty(name = "image.generation.provider", havingValue = "comfyui")
public class ComfyUIGenerationService implements ImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ComfyUIGenerationService.class);
    private static final int MAX_POLL_ATTEMPTS = 120;

    private final RestTemplate restTemplate;
    private final ImageStorageService imageStorageService;
    private final ObjectMapper objectMapper;
    private final String comfyUiUrl;
    private final String promptNodeId;
    private final String outputNodeId;
    private final String workflowTemplate;
    private final long pollIntervalMs;

    @Autowired
    public ComfyUIGenerationService(ImageStorageService imageStorageService,
                                    ObjectMapper objectMapper,
                                    @Value("${image.generation.comfyui.url:http://localhost:8188}") String comfyUiUrl,
                                    @Value("${image.generation.comfyui.workflow:comfyui-workflow-api.json}") String workflowResource,
                                    @Value("${image.generation.comfyui.prompt-node-id:6}") String promptNodeId,
                                    @Value("${image.generation.comfyui.output-node-id:9}") String outputNodeId) throws IOException {
        this.restTemplate = new RestTemplate();
        this.imageStorageService = imageStorageService;
        this.objectMapper = objectMapper;
        this.comfyUiUrl = comfyUiUrl;
        this.promptNodeId = promptNodeId;
        this.outputNodeId = outputNodeId;
        this.pollIntervalMs = 1000L;

        try (InputStream is = new ClassPathResource(workflowResource).getInputStream()) {
            this.workflowTemplate = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        log.info("ComfyUI service initialized — url={}, promptNode={}, outputNode={}", comfyUiUrl, promptNodeId, outputNodeId);
    }

    /** Package-private constructor for testing — allows injecting a mock RestTemplate. */
    ComfyUIGenerationService(ImageStorageService imageStorageService, String comfyUiUrl,
                             RestTemplate restTemplate, String workflowTemplate,
                             String promptNodeId, String outputNodeId) {
        this.restTemplate = restTemplate;
        this.imageStorageService = imageStorageService;
        this.objectMapper = new ObjectMapper();
        this.comfyUiUrl = comfyUiUrl;
        this.promptNodeId = promptNodeId;
        this.outputNodeId = outputNodeId;
        this.workflowTemplate = workflowTemplate;
        this.pollIntervalMs = 0L;
    }

    @Override
    @Async("promptleTaskExecutor")
    @SuppressWarnings("unchecked")
    public CompletableFuture<String> generateImage(String prompt) {
        try {
            // Build the workflow with the prompt injected and a random seed
            Map<String, Object> workflow = objectMapper.readValue(
                    workflowTemplate, new TypeReference<Map<String, Object>>() {});

            Map<String, Object> promptNode = (Map<String, Object>) workflow.get(promptNodeId);
            if (promptNode == null) {
                throw new RuntimeException("Prompt node '" + promptNodeId + "' not found in workflow template");
            }
            Map<String, Object> promptInputs = (Map<String, Object>) promptNode.get("inputs");
            promptInputs.put("text", prompt);

            // Randomize seed on the KSampler (node 3) for unique images
            for (Map.Entry<String, Object> entry : workflow.entrySet()) {
                Map<String, Object> node = (Map<String, Object>) entry.getValue();
                if ("KSampler".equals(node.get("class_type"))) {
                    Map<String, Object> inputs = (Map<String, Object>) node.get("inputs");
                    inputs.put("seed", ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE));
                }
            }

            // POST to ComfyUI /prompt API
            Map<String, Object> requestBody = Map.of("prompt", workflow);
            log.info("Submitting prompt to ComfyUI: '{}'", prompt);

            Map<String, Object> promptResponse = restTemplate.postForObject(
                    comfyUiUrl + "/prompt", requestBody, Map.class);
            if (promptResponse == null) {
                throw new RuntimeException("No response from ComfyUI");
            }
            String promptId = (String) promptResponse.get("prompt_id");
            log.info("ComfyUI accepted prompt — promptId={}", promptId);

            // Poll /history until the image is ready
            String gameId = UUID.randomUUID().toString();
            for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
                Map<String, Object> history = restTemplate.getForObject(
                        comfyUiUrl + "/history/" + promptId, Map.class);
                if (history != null && history.containsKey(promptId)) {
                    Map<String, Object> historyEntry = (Map<String, Object>) history.get(promptId);
                    Map<String, Object> outputs = (Map<String, Object>) historyEntry.get("outputs");
                    Map<String, Object> outputNode = (Map<String, Object>) outputs.get(outputNodeId);
                    if (outputNode == null) {
                        throw new RuntimeException("Output node '" + outputNodeId + "' not found in ComfyUI outputs");
                    }
                    List<Map<String, Object>> images = (List<Map<String, Object>>) outputNode.get("images");
                    String filename = (String) images.get(0).get("filename");

                    byte[] bytes = restTemplate.getForObject(
                            comfyUiUrl + "/view?filename=" + filename + "&type=output", byte[].class);
                    String url = imageStorageService.store(gameId, promptId, bytes);
                    log.info("Image generated and stored — promptId={}, url={}", promptId, url);
                    return CompletableFuture.completedFuture(url);
                }
                if (pollIntervalMs > 0 && attempt < MAX_POLL_ATTEMPTS - 1) {
                    Thread.sleep(pollIntervalMs);
                }
            }
            throw new RuntimeException("ComfyUI timed out after " + MAX_POLL_ATTEMPTS + " poll attempts");
        } catch (Exception e) {
            log.error("Image generation failed: {}", e.getMessage(), e);
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }
}
