package com.app.promptle.export.service;

import com.app.promptle.export.dto.ExportRequest;
import com.app.promptle.game.dto.ChainEntryDto;
import com.app.promptle.room.dto.PlayerDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    private final GifEncoder gifEncoder;
    private final String imageStoragePath;
    private final int gifWidth;
    private final int gifHeight;
    private final int textFrameDuration;
    private final int imageFrameDuration;
    private final int titleFrameDuration;
    private final int outroFrameDuration;

    public ExportService(GifEncoder gifEncoder,
                         @Value("${image.storage.local.path:/tmp/promptle}") String imageStoragePath,
                         @Value("${export.gif.width:540}") int gifWidth,
                         @Value("${export.gif.height:675}") int gifHeight,
                         @Value("${export.gif.text-frame-duration:4}") int textFrameDuration,
                         @Value("${export.gif.image-frame-duration:3}") int imageFrameDuration,
                         @Value("${export.gif.title-frame-duration:3}") int titleFrameDuration,
                         @Value("${export.gif.outro-frame-duration:3}") int outroFrameDuration) {
        this.gifEncoder = gifEncoder;
        this.imageStoragePath = imageStoragePath;
        this.gifWidth = gifWidth;
        this.gifHeight = gifHeight;
        this.textFrameDuration = textFrameDuration;
        this.imageFrameDuration = imageFrameDuration;
        this.titleFrameDuration = titleFrameDuration;
        this.outroFrameDuration = outroFrameDuration;
    }

    public byte[] exportChain(ExportRequest request, String roomCode) {
        Map<String, String> playerAliases = request.players().stream()
                .collect(Collectors.toMap(PlayerDto::id, PlayerDto::alias));

        String originPlayerId = request.chain().entries().get(0).playerId();
        String originPlayerName = playerAliases.getOrDefault(originPlayerId, "Unknown");

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("promptle-export-");
            log.info("Exporting chain for room {} to {}", roomCode, tempDir);

            FrameRenderer renderer = new FrameRenderer(
                    gifWidth, gifHeight,
                    textFrameDuration, imageFrameDuration,
                    titleFrameDuration, outroFrameDuration);

            renderer.renderTitleFrame(tempDir, originPlayerName, roomCode);

            for (ChainEntryDto entry : request.chain().entries()) {
                String playerName = playerAliases.getOrDefault(entry.playerId(), "Unknown");
                renderer.renderTextFrame(tempDir, entry.text(), playerName,
                        entry.avatarId(), entry.isPlaceholder());

                if (entry.imageUrl() != null && !entry.imageUrl().isBlank()) {
                    byte[] imageBytes = fetchImageBytes(entry.imageUrl());
                    if (imageBytes != null) {
                        renderer.renderImageFrame(tempDir, imageBytes);
                    }
                }
            }

            renderer.renderOutroFrame(tempDir);
            renderer.writeFrameList(tempDir);

            byte[] gifBytes = gifEncoder.encode(tempDir);
            log.info("Export completed for room {}: {} frames, {} bytes",
                    roomCode, renderer.getFrameCount(), gifBytes.length);
            return gifBytes;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to export chain for room " + roomCode, e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to export chain for room " + roomCode, e);
        } finally {
            if (tempDir != null) {
                deleteTempDir(tempDir);
            }
        }
    }

    private byte[] fetchImageBytes(String imageUrl) {
        try {
            // imageUrl format: /api/images/{gameId}/{imageId}
            String[] parts = imageUrl.split("/");
            if (parts.length < 4) {
                log.warn("Invalid image URL format: {}", imageUrl);
                return null;
            }
            String gameId = parts[parts.length - 2];
            String imageId = parts[parts.length - 1];

            Path imagePath = Path.of(imageStoragePath, gameId, imageId + ".png").normalize();
            if (!imagePath.startsWith(Path.of(imageStoragePath).normalize())) {
                log.warn("Path traversal attempt detected: {}", imageUrl);
                return null;
            }
            if (!Files.exists(imagePath)) {
                log.warn("Image file not found: {}", imagePath);
                return null;
            }
            return Files.readAllBytes(imagePath);
        } catch (IOException e) {
            log.warn("Failed to read image from {}: {}", imageUrl, e.getMessage());
            return null;
        }
    }

    private void deleteTempDir(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Failed to clean up temp directory {}: {}", dir, e.getMessage());
        }
    }
}
