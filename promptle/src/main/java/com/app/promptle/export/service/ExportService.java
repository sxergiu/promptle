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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    private final GifEncoder gifEncoder;
    private final String imageStoragePath;
    private final int gifWidth;
    private final int gifHeight;
    private final int outputWidth;
    private final int fps;
    private final double transitionDuration;
    private final double textFrameDuration;
    private final double imageFrameDuration;
    private final double titleFrameDuration;
    private final double outroFrameDuration;

    public ExportService(GifEncoder gifEncoder,
                         @Value("${image.storage.local.path:/tmp/promptle}") String imageStoragePath,
                         @Value("${export.gif.width:1080}") int gifWidth,
                         @Value("${export.gif.height:1350}") int gifHeight,
                         @Value("${export.gif.output-width:540}") int outputWidth,
                         @Value("${export.gif.fps:20}") int fps,
                         @Value("${export.gif.transition-duration:0.45}") double transitionDuration,
                         @Value("${export.gif.text-frame-duration:2.2}") double textFrameDuration,
                         @Value("${export.gif.image-frame-duration:2.2}") double imageFrameDuration,
                         @Value("${export.gif.title-frame-duration:1.8}") double titleFrameDuration,
                         @Value("${export.gif.outro-frame-duration:2.0}") double outroFrameDuration) {
        this.gifEncoder = gifEncoder;
        this.imageStoragePath = imageStoragePath;
        this.gifWidth = gifWidth;
        this.gifHeight = gifHeight;
        this.outputWidth = outputWidth;
        this.fps = fps;
        this.transitionDuration = transitionDuration;
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

            List<ChainEntryDto> entries = request.chain().entries();
            int total = entries.size();
            for (int i = 0; i < total; i++) {
                ChainEntryDto entry = entries.get(i);
                int step = i + 1;
                String playerName = playerAliases.getOrDefault(entry.playerId(), "Unknown");
                String role = i == 0 ? "Prompted" : "Guessed";
                renderer.renderTextFrame(tempDir, entry.text(), playerName,
                        entry.avatarId(), entry.isPlaceholder(), step, total, role);

                if (entry.imageUrl() != null && !entry.imageUrl().isBlank()) {
                    byte[] imageBytes = fetchImageBytes(entry.imageUrl());
                    if (imageBytes != null) {
                        renderer.renderImageFrame(tempDir, imageBytes, step, total);
                    }
                }
            }

            renderer.renderOutroFrame(tempDir, roomCode);

            byte[] gifBytes = gifEncoder.encode(
                    tempDir, renderer.getFrames(), transitionDuration, fps, outputWidth);
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
