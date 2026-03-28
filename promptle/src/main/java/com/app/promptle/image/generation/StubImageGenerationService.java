package com.app.promptle.image.generation;

import com.app.promptle.image.api.ImageGenerationService;
import com.app.promptle.image.api.ImageStorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
@ConditionalOnProperty(name = "image.generation.provider", havingValue = "stub")
public class StubImageGenerationService implements ImageGenerationService {

    private final ImageStorageService imageStorageService;

    public StubImageGenerationService(ImageStorageService imageStorageService) {
        this.imageStorageService = imageStorageService;
    }

    @Override
    @Async("promptleTaskExecutor")
    public CompletableFuture<String> generateImage(String prompt) {
        try {
            byte[] png = createColoredPng(320, 240);
            String url = imageStorageService.store("stub", UUID.randomUUID().toString(), png);
            return CompletableFuture.completedFuture(url);
        } catch (Exception e) {
            CompletableFuture<String> f = new CompletableFuture<>();
            f.completeExceptionally(e);
            return f;
        }
    }

    private byte[] createColoredPng(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x6B2FBE));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }
}
