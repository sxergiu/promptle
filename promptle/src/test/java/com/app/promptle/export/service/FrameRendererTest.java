package com.app.promptle.export.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FrameRendererTest {

    private FrameRenderer frameRenderer;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        frameRenderer = new FrameRenderer(540, 675, 4, 3, 3, 3);
        tempDir = Files.createTempDirectory("frame-renderer-test-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walkFileTree(tempDir, new SimpleFileVisitor<>() {
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

    // ---- helpers ----

    private byte[] createTestPng(int width, int height) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.DARK_GRAY);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private void assertImageDimensions(Path imagePath, int expectedWidth, int expectedHeight) throws IOException {
        BufferedImage img = ImageIO.read(imagePath.toFile());
        assertNotNull(img, "Image should be readable: " + imagePath);
        assertEquals(expectedWidth, img.getWidth(), "Image width mismatch");
        assertEquals(expectedHeight, img.getHeight(), "Image height mismatch");
    }

    // ---- renderTitleFrame tests ----

    @Test
    void renderTitleFrame_CreatesFile_WithCorrectDimensions() throws IOException {
        frameRenderer.renderTitleFrame(tempDir, "Alice", "ABCD1234");

        Path framePath = tempDir.resolve("frame-001.png");
        assertTrue(Files.exists(framePath), "Title frame file should exist");
        assertImageDimensions(framePath, 540, 675);
    }

    @Test
    void renderTitleFrame_CreatesFile_WithSequentialName() throws IOException {
        frameRenderer.renderTitleFrame(tempDir, "Alice", "ABCD1234");

        assertTrue(Files.exists(tempDir.resolve("frame-001.png")), "First frame should be frame-001.png");
        assertEquals(1, frameRenderer.getFrameCount());
    }

    // ---- renderTextFrame tests ----

    @Test
    void renderTextFrame_CreatesFile_WithCorrectDimensions() throws IOException {
        frameRenderer.renderTextFrame(tempDir, "A beautiful sunset", "Alice", "icon-1", false);

        Path framePath = tempDir.resolve("frame-001.png");
        assertTrue(Files.exists(framePath), "Text frame file should exist");
        assertImageDimensions(framePath, 540, 675);
    }

    @Test
    void renderTextFrame_LongText_DoesNotThrow() throws IOException {
        String longText = "A ".repeat(300);
        assertDoesNotThrow(() ->
                frameRenderer.renderTextFrame(tempDir, longText, "Alice", "icon-1", false));

        assertTrue(Files.exists(tempDir.resolve("frame-001.png")));
    }

    @Test
    void renderTextFrame_EmptyText_DoesNotThrow() throws IOException {
        assertDoesNotThrow(() ->
                frameRenderer.renderTextFrame(tempDir, "", "Alice", "icon-1", false));

        assertTrue(Files.exists(tempDir.resolve("frame-001.png")));
    }

    @Test
    void renderTextFrame_PlaceholderEntry_DoesNotThrow() throws IOException {
        assertDoesNotThrow(() ->
                frameRenderer.renderTextFrame(tempDir, "Timed out", "Alice", "icon-1", true));

        assertTrue(Files.exists(tempDir.resolve("frame-001.png")));
    }

    @Test
    void renderTextFrame_NullAvatarId_FallsBackToDefault() throws IOException {
        assertDoesNotThrow(() ->
                frameRenderer.renderTextFrame(tempDir, "Some text", "Alice", null, false));

        Path framePath = tempDir.resolve("frame-001.png");
        assertTrue(Files.exists(framePath), "Frame should still be created with null avatarId");
        assertImageDimensions(framePath, 540, 675);
    }

    @Test
    void renderTextFrame_UnknownAvatarId_FallsBackToDefault() throws IOException {
        assertDoesNotThrow(() ->
                frameRenderer.renderTextFrame(tempDir, "Some text", "Alice", "icon-99", false));

        Path framePath = tempDir.resolve("frame-001.png");
        assertTrue(Files.exists(framePath), "Frame should still be created with unknown avatarId");
        assertImageDimensions(framePath, 540, 675);
    }

    // ---- renderImageFrame tests ----

    @Test
    void renderImageFrame_ValidPng_CreatesFile() throws IOException {
        byte[] pngBytes = createTestPng(512, 512);

        frameRenderer.renderImageFrame(tempDir, pngBytes);

        Path framePath = tempDir.resolve("frame-001.png");
        assertTrue(Files.exists(framePath), "Image frame file should exist");
        assertImageDimensions(framePath, 540, 675);
    }

    @Test
    void renderImageFrame_CorruptImageBytes_ThrowsOrSkips() {
        byte[] garbage = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05};

        // Corrupt image bytes should either throw an IOException or produce a frame
        // without the generated image embedded (implementation-dependent)
        try {
            frameRenderer.renderImageFrame(tempDir, garbage);
            // If it doesn't throw, frame should still exist
            assertTrue(Files.exists(tempDir.resolve("frame-001.png")));
        } catch (IOException e) {
            // Expected — corrupt bytes cannot be decoded
            assertNotNull(e.getMessage());
        }
    }

    // ---- renderOutroFrame tests ----

    @Test
    void renderOutroFrame_CreatesFile_WithCorrectDimensions() throws IOException {
        frameRenderer.renderOutroFrame(tempDir);

        Path framePath = tempDir.resolve("frame-001.png");
        assertTrue(Files.exists(framePath), "Outro frame file should exist");
        assertImageDimensions(framePath, 540, 675);
    }

    // ---- multiple frames tests ----

    @Test
    void renderMultipleFrames_SequentialNaming() throws IOException {
        byte[] pngBytes = createTestPng(256, 256);

        frameRenderer.renderTitleFrame(tempDir, "Alice", "ABCD1234");
        frameRenderer.renderTextFrame(tempDir, "First prompt", "Alice", "icon-1", false);
        frameRenderer.renderTextFrame(tempDir, "Second prompt", "Bob", "icon-2", false);
        frameRenderer.renderImageFrame(tempDir, pngBytes);
        frameRenderer.renderOutroFrame(tempDir);

        assertEquals(5, frameRenderer.getFrameCount());
        assertTrue(Files.exists(tempDir.resolve("frame-001.png")), "frame-001 should exist");
        assertTrue(Files.exists(tempDir.resolve("frame-002.png")), "frame-002 should exist");
        assertTrue(Files.exists(tempDir.resolve("frame-003.png")), "frame-003 should exist");
        assertTrue(Files.exists(tempDir.resolve("frame-004.png")), "frame-004 should exist");
        assertTrue(Files.exists(tempDir.resolve("frame-005.png")), "frame-005 should exist");
    }

    @Test
    void renderMultipleFrames_WritesFrameListFile() throws IOException {
        frameRenderer.renderTitleFrame(tempDir, "Alice", "ABCD1234");
        frameRenderer.renderTextFrame(tempDir, "Prompt text", "Alice", "icon-1", false);
        frameRenderer.renderOutroFrame(tempDir);

        frameRenderer.writeFrameList(tempDir);

        Path framesFile = tempDir.resolve("frames.txt");
        assertTrue(Files.exists(framesFile), "frames.txt should exist after writeFrameList()");
        String content = Files.readString(framesFile);
        assertFalse(content.isBlank(), "frames.txt should not be empty");
    }

    @Test
    void renderMultipleFrames_FrameListHasCorrectDurations() throws IOException {
        byte[] pngBytes = createTestPng(256, 256);

        // title (duration=3), text (duration=4), image (duration=3), outro (duration=3)
        frameRenderer.renderTitleFrame(tempDir, "Alice", "ABCD1234");
        frameRenderer.renderTextFrame(tempDir, "A prompt", "Alice", "icon-1", false);
        frameRenderer.renderImageFrame(tempDir, pngBytes);
        frameRenderer.renderOutroFrame(tempDir);

        frameRenderer.writeFrameList(tempDir);

        String content = Files.readString(tempDir.resolve("frames.txt"));
        String[] lines = content.split("\n");

        // Each frame has 2 lines: "file '...'" and "duration N"
        assertEquals(8, lines.length, "Expected 8 lines (4 frames x 2 lines each)");

        // Verify frame filenames and durations
        assertTrue(lines[0].contains("frame-001.png"), "First file entry");
        assertTrue(lines[1].contains("duration 3"), "Title frame duration should be 3");

        assertTrue(lines[2].contains("frame-002.png"), "Second file entry");
        assertTrue(lines[3].contains("duration 4"), "Text frame duration should be 4");

        assertTrue(lines[4].contains("frame-003.png"), "Third file entry");
        assertTrue(lines[5].contains("duration 3"), "Image frame duration should be 3");

        assertTrue(lines[6].contains("frame-004.png"), "Fourth file entry");
        assertTrue(lines[7].contains("duration 3"), "Outro frame duration should be 3");
    }
}
