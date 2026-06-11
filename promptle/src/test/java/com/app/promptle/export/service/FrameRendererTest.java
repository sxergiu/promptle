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

    // Durations passed to the constructor: title=3, text=4, image=3, outro=3.

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
        frameRenderer.renderTextFrame(tempDir, "A beautiful sunset", "Alice", "icon-1", false, 1, 3, "Prompted");

        Path framePath = tempDir.resolve("frame-001.png");
        assertTrue(Files.exists(framePath), "Text frame file should exist");
        assertImageDimensions(framePath, 540, 675);
    }

    @Test
    void renderTextFrame_LongText_DoesNotThrow() throws IOException {
        String longText = "A ".repeat(300);
        assertDoesNotThrow(() ->
                frameRenderer.renderTextFrame(tempDir, longText, "Alice", "icon-1", false, 1, 3, "Prompted"));

        assertTrue(Files.exists(tempDir.resolve("frame-001.png")));
    }

    @Test
    void renderTextFrame_EmptyText_DoesNotThrow() throws IOException {
        assertDoesNotThrow(() ->
                frameRenderer.renderTextFrame(tempDir, "", "Alice", "icon-1", false, 1, 3, "Prompted"));

        assertTrue(Files.exists(tempDir.resolve("frame-001.png")));
    }

    @Test
    void renderTextFrame_PlaceholderEntry_DoesNotThrow() throws IOException {
        assertDoesNotThrow(() ->
                frameRenderer.renderTextFrame(tempDir, "Timed out", "Alice", "icon-1", true, 2, 3, "Guessed"));

        assertTrue(Files.exists(tempDir.resolve("frame-001.png")));
    }

    @Test
    void renderTextFrame_NullAvatarId_FallsBackToDefault() throws IOException {
        assertDoesNotThrow(() ->
                frameRenderer.renderTextFrame(tempDir, "Some text", "Alice", null, false, 1, 3, "Prompted"));

        Path framePath = tempDir.resolve("frame-001.png");
        assertTrue(Files.exists(framePath), "Frame should still be created with null avatarId");
        assertImageDimensions(framePath, 540, 675);
    }

    @Test
    void renderTextFrame_UnknownAvatarId_FallsBackToDefault() throws IOException {
        assertDoesNotThrow(() ->
                frameRenderer.renderTextFrame(tempDir, "Some text", "Alice", "icon-99", false, 1, 3, "Prompted"));

        Path framePath = tempDir.resolve("frame-001.png");
        assertTrue(Files.exists(framePath), "Frame should still be created with unknown avatarId");
        assertImageDimensions(framePath, 540, 675);
    }

    // ---- renderImageFrame tests ----

    @Test
    void renderImageFrame_ValidPng_CreatesFile() throws IOException {
        byte[] pngBytes = createTestPng(512, 512);

        frameRenderer.renderImageFrame(tempDir, pngBytes, 1, 3);

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
            frameRenderer.renderImageFrame(tempDir, garbage, 1, 3);
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
        frameRenderer.renderOutroFrame(tempDir, "ABCD1234");

        Path framePath = tempDir.resolve("frame-001.png");
        assertTrue(Files.exists(framePath), "Outro frame file should exist");
        assertImageDimensions(framePath, 540, 675);
    }

    // ---- multiple frames tests ----

    @Test
    void renderMultipleFrames_SequentialNaming() throws IOException {
        byte[] pngBytes = createTestPng(256, 256);

        frameRenderer.renderTitleFrame(tempDir, "Alice", "ABCD1234");
        frameRenderer.renderTextFrame(tempDir, "First prompt", "Alice", "icon-1", false, 1, 2, "Prompted");
        frameRenderer.renderTextFrame(tempDir, "Second prompt", "Bob", "icon-2", false, 2, 2, "Guessed");
        frameRenderer.renderImageFrame(tempDir, pngBytes, 2, 2);
        frameRenderer.renderOutroFrame(tempDir, "ABCD1234");

        assertEquals(5, frameRenderer.getFrameCount());
        assertTrue(Files.exists(tempDir.resolve("frame-001.png")), "frame-001 should exist");
        assertTrue(Files.exists(tempDir.resolve("frame-002.png")), "frame-002 should exist");
        assertTrue(Files.exists(tempDir.resolve("frame-003.png")), "frame-003 should exist");
        assertTrue(Files.exists(tempDir.resolve("frame-004.png")), "frame-004 should exist");
        assertTrue(Files.exists(tempDir.resolve("frame-005.png")), "frame-005 should exist");
    }

    @Test
    void getFrames_ExposesOrderedFramesWithDurations() throws IOException {
        byte[] pngBytes = createTestPng(256, 256);

        // title (3), text (4), image (3), outro (3)
        frameRenderer.renderTitleFrame(tempDir, "Alice", "ABCD1234");
        frameRenderer.renderTextFrame(tempDir, "A prompt", "Alice", "icon-1", false, 1, 1, "Prompted");
        frameRenderer.renderImageFrame(tempDir, pngBytes, 1, 1);
        frameRenderer.renderOutroFrame(tempDir, "ABCD1234");

        var frames = frameRenderer.getFrames();
        assertEquals(4, frames.size(), "Expected 4 frame specs");

        assertEquals("frame-001.png", frames.get(0).filename());
        assertEquals(3.0, frames.get(0).duration(), 0.0001, "Title frame duration");

        assertEquals("frame-002.png", frames.get(1).filename());
        assertEquals(4.0, frames.get(1).duration(), 0.0001, "Text frame duration");

        assertEquals("frame-003.png", frames.get(2).filename());
        assertEquals(3.0, frames.get(2).duration(), 0.0001, "Image frame duration");

        assertEquals("frame-004.png", frames.get(3).filename());
        assertEquals(3.0, frames.get(3).duration(), 0.0001, "Outro frame duration");
    }
}
