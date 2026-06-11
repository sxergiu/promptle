package com.app.promptle.export.service;

import com.app.promptle.export.service.FrameRenderer.FrameSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GifEncoderTest {

    private static final double TRANSITION = 0.4;
    private static final int FPS = 15;
    private static final int OUTPUT_WIDTH = 540;

    private GifEncoder gifEncoder;
    private final List<Path> tempDirs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        gifEncoder = new GifEncoder("ffmpeg");
    }

    @AfterEach
    void tearDown() throws IOException {
        for (Path dir : tempDirs) {
            if (dir != null && Files.exists(dir)) {
                Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
    }

    // ---- helpers ----

    /** Two solid-color frames + the FrameSpec list the encoder consumes. */
    private List<FrameSpec> createSampleFrames(Path dir) throws IOException {
        List<FrameSpec> frames = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            BufferedImage img = new BufferedImage(540, 675, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setColor(i == 1 ? Color.RED : Color.BLUE);
            g.fillRect(0, 0, 540, 675);
            g.dispose();
            String name = String.format("frame-%03d.png", i);
            ImageIO.write(img, "png", dir.resolve(name).toFile());
            frames.add(new FrameSpec(name, 1.5));
        }
        return frames;
    }

    private Path createTempDir() throws IOException {
        Path dir = Files.createTempDirectory("gif-encoder-test-");
        tempDirs.add(dir);
        return dir;
    }

    // ---- tests ----

    @Test
    @Tag("requires-ffmpeg")
    void encode_ValidFrames_ReturnsNonEmptyBytes() throws Exception {
        Path dir = createTempDir();
        List<FrameSpec> frames = createSampleFrames(dir);

        byte[] result = gifEncoder.encode(dir, frames, TRANSITION, FPS, OUTPUT_WIDTH);

        assertNotNull(result, "Encoded bytes should not be null");
        assertTrue(result.length > 0, "Encoded bytes should not be empty");
    }

    @Test
    @Tag("requires-ffmpeg")
    void encode_OutputStartsWithGifMagicBytes() throws Exception {
        Path dir = createTempDir();
        List<FrameSpec> frames = createSampleFrames(dir);

        byte[] result = gifEncoder.encode(dir, frames, TRANSITION, FPS, OUTPUT_WIDTH);

        assertTrue(result.length >= 6, "GIF output must be at least 6 bytes");
        String magic = new String(result, 0, 6, StandardCharsets.US_ASCII);
        assertEquals("GIF89a", magic, "Output should start with GIF89a magic bytes");
    }

    @Test
    @Tag("requires-ffmpeg")
    void encode_SingleFrame_ReturnsGif() throws Exception {
        Path dir = createTempDir();
        List<FrameSpec> frames = List.of(createSampleFrames(dir).get(0));

        byte[] result = gifEncoder.encode(dir, frames, TRANSITION, FPS, OUTPUT_WIDTH);

        assertTrue(result.length > 0, "Single-frame GIF should still encode");
    }

    @Test
    @Tag("requires-ffmpeg")
    void encode_ProducesOutputGifFile() throws Exception {
        Path dir = createTempDir();
        List<FrameSpec> frames = createSampleFrames(dir);

        gifEncoder.encode(dir, frames, TRANSITION, FPS, OUTPUT_WIDTH);

        assertTrue(Files.exists(dir.resolve("output.gif")), "output.gif should be produced");
    }

    @Test
    void encode_EmptyFrames_ThrowsException() throws IOException {
        Path dir = createTempDir();

        assertThrows(IllegalArgumentException.class,
                () -> gifEncoder.encode(dir, List.of(), TRANSITION, FPS, OUTPUT_WIDTH),
                "Encoding an empty frame list should throw");
    }

    @Test
    void encode_FfmpegNotFound_ThrowsException() throws IOException {
        GifEncoder badEncoder = new GifEncoder("/nonexistent/ffmpeg");
        Path dir = createTempDir();
        List<FrameSpec> frames = createSampleFrames(dir);

        assertThrows(Exception.class,
                () -> badEncoder.encode(dir, frames, TRANSITION, FPS, OUTPUT_WIDTH),
                "Non-existent ffmpeg path should throw an exception");
    }

    @Test
    void encode_FfmpegExitsNonZero_ThrowsException() throws IOException {
        GifEncoder badEncoder = new GifEncoder("false");
        Path dir = createTempDir();
        List<FrameSpec> frames = createSampleFrames(dir);

        assertThrows(Exception.class,
                () -> badEncoder.encode(dir, frames, TRANSITION, FPS, OUTPUT_WIDTH),
                "FFmpeg exiting non-zero should throw an exception");
    }
}
