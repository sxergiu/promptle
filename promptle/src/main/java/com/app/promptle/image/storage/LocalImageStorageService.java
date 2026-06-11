package com.app.promptle.image.storage;

import com.app.promptle.image.api.ImageStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Stores images on the local filesystem.
 * Active when image.storage.provider=local.
 */
@Service
@ConditionalOnProperty(name = "image.storage.provider", havingValue = "local")
public class LocalImageStorageService implements ImageStorageService {

    private final String basePath;

    public LocalImageStorageService(@Value("${image.storage.local.path:/tmp/promptle}") String basePath) {
        this.basePath = basePath;
    }

    @Override
    public String store(String gameId, String imageId, byte[] bytes) {
        try {
            Path dir = Paths.get(basePath, gameId);
            Files.createDirectories(dir);
            Path file = dir.resolve(imageId + ".png");
            Files.write(file, bytes);
            return "/api/images/" + gameId + "/" + imageId;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void deleteImages(List<String> urls) {
        // URL format: /api/images/{gameId}/{imageId}
        for (String url : urls) {
            String[] parts = url.split("/");
            if (parts.length < 5) continue; // malformed, skip
            String gameId = parts[3];
            String imageId = parts[4];
            Path file = Paths.get(basePath, gameId, imageId + ".png");
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public byte[] fetchImageBytes(String imageUrl) {
        String[] parts = imageUrl.split("/");
        if (parts.length < 5) {
            throw new IllegalArgumentException("Malformed image URL: " + imageUrl);
        }
        String gameId = parts[3];
        String imageId = parts[4];
        Path file = Paths.get(basePath, gameId, imageId + ".png");
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void deleteGame(String gameId) {
        Path dir = Paths.get(basePath, gameId);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
