package com.app.promptle.image.generation;

import com.app.promptle.image.api.ImageGenerationService;
import com.app.promptle.image.api.ImageStorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
@ConditionalOnProperty(name = "image.generation.provider", havingValue = "stub")
public class StubImageGenerationService implements ImageGenerationService {

    private final ImageStorageService imageStorageService;
    private final byte[] stubImageBytes;

    public StubImageGenerationService(ImageStorageService imageStorageService) throws IOException {
        this.imageStorageService = imageStorageService;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("stub-image.png")) {
            if (is == null) throw new IOException("stub-image.png not found on classpath");
            this.stubImageBytes = is.readAllBytes();
        }
    }

    @Override
    @Async("promptleTaskExecutor")
    public CompletableFuture<String> generateImage(String prompt) {
        try {
            String url = imageStorageService.store("stub", UUID.randomUUID().toString(), stubImageBytes);
            return CompletableFuture.completedFuture(url);
        } catch (Exception e) {
            CompletableFuture<String> f = new CompletableFuture<>();
            f.completeExceptionally(e);
            return f;
        }
    }
}
