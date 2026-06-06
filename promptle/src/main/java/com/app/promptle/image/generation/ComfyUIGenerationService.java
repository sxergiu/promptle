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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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
    private static final int MAX_POLL_ATTEMPTS = 240;

    private final RestTemplate restTemplate;
    private final ImageStorageService imageStorageService;
    private final ObjectMapper objectMapper;
    private final String comfyUiUrl;
    private final String promptNodeId;
    private final String outputNodeId;
    private final String workflowTemplate;
    private final long pollIntervalMs;
    private final String img2imgWorkflowTemplate;
    private final double img2imgDenoise;
    private final String img2imgLoadImageNodeId;
    private final String img2imgPromptNodeId;
    private final String img2imgOutputNodeId;

    @Autowired
    public ComfyUIGenerationService(ImageStorageService imageStorageService,
                                    ObjectMapper objectMapper,
                                    @Value("${image.generation.comfyui.url:http://localhost:8188}") String comfyUiUrl,
                                    @Value("${image.generation.comfyui.workflow:workflows/proto-t2i.json}") String workflowResource,
                                    @Value("${image.generation.comfyui.prompt-node-id:6}") String promptNodeId,
                                    @Value("${image.generation.comfyui.output-node-id:9}") String outputNodeId,
                                    @Value("${image.generation.comfyui.img2img-workflow:#{null}}") String img2imgWorkflowResource,
                                    @Value("${image.generation.comfyui.img2img-denoise:0.55}") double img2imgDenoise,
                                    @Value("${image.generation.comfyui.img2img-load-image-node-id:10}") String img2imgLoadImageNodeId,
                                    @Value("${image.generation.comfyui.img2img-prompt-node-id:8}") String img2imgPromptNodeId,
                                    @Value("${image.generation.comfyui.img2img-output-node-id:4}") String img2imgOutputNodeId) throws IOException {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(30_000);
        this.restTemplate = new RestTemplate(factory);
        this.imageStorageService = imageStorageService;
        this.objectMapper = objectMapper;
        this.comfyUiUrl = comfyUiUrl;
        this.promptNodeId = promptNodeId;
        this.outputNodeId = outputNodeId;
        this.pollIntervalMs = 1000L;
        this.img2imgDenoise = img2imgDenoise;
        this.img2imgLoadImageNodeId = img2imgLoadImageNodeId;
        this.img2imgPromptNodeId = img2imgPromptNodeId;
        this.img2imgOutputNodeId = img2imgOutputNodeId;

        try (InputStream is = new ClassPathResource(workflowResource).getInputStream()) {
            this.workflowTemplate = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        if (img2imgWorkflowResource != null) {
            try (InputStream is = new ClassPathResource(img2imgWorkflowResource).getInputStream()) {
                this.img2imgWorkflowTemplate = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } else {
            this.img2imgWorkflowTemplate = null;
        }

        log.info("ComfyUI service initialized — url={}, promptNode={}, outputNode={}, img2img={}",
                comfyUiUrl, promptNodeId, outputNodeId, img2imgWorkflowTemplate != null);
    }

    /** Package-private constructor for testing — allows injecting a mock RestTemplate. */
    ComfyUIGenerationService(ImageStorageService imageStorageService, String comfyUiUrl,
                             RestTemplate restTemplate, String workflowTemplate,
                             String promptNodeId, String outputNodeId,
                             String img2imgWorkflowTemplate, double img2imgDenoise,
                             String img2imgLoadImageNodeId, String img2imgPromptNodeId,
                             String img2imgOutputNodeId) {
        this.restTemplate = restTemplate;
        this.imageStorageService = imageStorageService;
        this.objectMapper = new ObjectMapper();
        this.comfyUiUrl = comfyUiUrl;
        this.promptNodeId = promptNodeId;
        this.outputNodeId = outputNodeId;
        this.workflowTemplate = workflowTemplate;
        this.pollIntervalMs = 0L;
        this.img2imgWorkflowTemplate = img2imgWorkflowTemplate;
        this.img2imgDenoise = img2imgDenoise;
        this.img2imgLoadImageNodeId = img2imgLoadImageNodeId;
        this.img2imgPromptNodeId = img2imgPromptNodeId;
        this.img2imgOutputNodeId = img2imgOutputNodeId;
    }

    @Override
    @Async("promptleTaskExecutor")
    @SuppressWarnings("unchecked")
    public CompletableFuture<String> generateImage(String prompt) {
        try {
            Map<String, Object> workflow = objectMapper.readValue(
                    workflowTemplate, new TypeReference<Map<String, Object>>() {});

            Map<String, Object> promptNode = (Map<String, Object>) workflow.get(promptNodeId);
            if (promptNode == null) {
                throw new RuntimeException("Prompt node '" + promptNodeId + "' not found in workflow template");
            }
            Map<String, Object> promptInputs = (Map<String, Object>) promptNode.get("inputs");
            promptInputs.put("text", prompt);

            for (Map.Entry<String, Object> entry : workflow.entrySet()) {
                Map<String, Object> node = (Map<String, Object>) entry.getValue();
                if ("KSampler".equals(node.get("class_type"))) {
                    Map<String, Object> inputs = (Map<String, Object>) node.get("inputs");
                    inputs.put("seed", ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE));
                }
            }

            return submitAndWaitForImage(workflow, prompt, outputNodeId);
        } catch (Exception e) {
            log.error("Image generation failed: {}", e.getMessage(), e);
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    @Override
    @Async("promptleTaskExecutor")
    @SuppressWarnings("unchecked")
    public CompletableFuture<String> generateImageFromImage(String prompt, byte[] previousImageBytes) {
        if (img2imgWorkflowTemplate == null) {
            log.warn("img2img workflow not configured, falling back to txt2img");
            return generateImage(prompt);
        }
        try {
            String uploadedFilename = uploadImageToComfyUI(previousImageBytes);
            log.info("Uploaded previous image to ComfyUI: {}", uploadedFilename);

            Map<String, Object> workflow = objectMapper.readValue(
                    img2imgWorkflowTemplate, new TypeReference<Map<String, Object>>() {});

            Map<String, Object> promptNode = (Map<String, Object>) workflow.get(img2imgPromptNodeId);
            ((Map<String, Object>) promptNode.get("inputs")).put("text", prompt);

            Map<String, Object> loadImageNode = (Map<String, Object>) workflow.get(img2imgLoadImageNodeId);
            ((Map<String, Object>) loadImageNode.get("inputs")).put("image", uploadedFilename);

            for (Map.Entry<String, Object> entry : workflow.entrySet()) {
                Map<String, Object> node = (Map<String, Object>) entry.getValue();
                if ("KSampler".equals(node.get("class_type"))) {
                    Map<String, Object> inputs = (Map<String, Object>) node.get("inputs");
                    inputs.put("seed", ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE));
                    inputs.put("denoise", img2imgDenoise);
                }
            }

            return submitAndWaitForImage(workflow, prompt, img2imgOutputNodeId);
        } catch (Exception e) {
            log.error("img2img generation failed: {}", e.getMessage(), e);
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<String> submitAndWaitForImage(Map<String, Object> workflow, String prompt, String outNodeId)
            throws Exception {
        Map<String, Object> requestBody = Map.of("prompt", workflow);
        log.info("Submitting prompt to ComfyUI: '{}'", prompt);

        Map<String, Object> promptResponse = restTemplate.postForObject(
                comfyUiUrl + "/prompt", requestBody, Map.class);
        if (promptResponse == null) {
            throw new RuntimeException("No response from ComfyUI");
        }
        String promptId = (String) promptResponse.get("prompt_id");
        log.info("ComfyUI accepted prompt — promptId={}", promptId);

        String gameId = UUID.randomUUID().toString();
        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            Map<String, Object> history = restTemplate.getForObject(
                    comfyUiUrl + "/history/" + promptId, Map.class);
            if (history != null && history.containsKey(promptId)) {
                Map<String, Object> historyEntry = (Map<String, Object>) history.get(promptId);
                Map<String, Object> outputs = (Map<String, Object>) historyEntry.get("outputs");
                Map<String, Object> outputNode = (Map<String, Object>) outputs.get(outNodeId);
                if (outputNode == null) {
                    throw new RuntimeException("Output node '" + outNodeId + "' not found in ComfyUI outputs");
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
    }

    @SuppressWarnings("unchecked")
    private String uploadImageToComfyUI(byte[] imageBytes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return "prev_" + UUID.randomUUID() + ".png";
            }
        };
        body.add("image", new HttpEntity<>(resource, new HttpHeaders()));
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);
        Map<String, Object> response = restTemplate.postForObject(
                comfyUiUrl + "/upload/image", request, Map.class);
        return (String) response.get("name");
    }
}
