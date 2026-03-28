package com.app.promptle.image.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalImageStorageServiceTest {

    @TempDir
    Path tempDir;

    private LocalImageStorageService service;

    @BeforeEach
    void setUp() {
        service = new LocalImageStorageService(tempDir.toString());
    }

    // ---- store ----

    @Test
    void store_WritesBytesToCorrectPath_AndFileExists() throws IOException {
        byte[] bytes = "fake-png-bytes".getBytes();

        service.store("game-abc", "img-001", bytes);

        Path expectedPath = tempDir.resolve("game-abc").resolve("img-001.png");
        assertTrue(Files.exists(expectedPath), "Expected file to exist at: " + expectedPath);
        assertArrayEquals(bytes, Files.readAllBytes(expectedPath));
    }

    @Test
    void store_ReturnsCorrectUrl() {
        byte[] bytes = new byte[]{1, 2, 3};

        String url = service.store("game-xyz", "img-42", bytes);

        assertEquals("/api/images/game-xyz/img-42", url);
    }

    @Test
    void store_CreatesSubdirectory_WhenItDoesNotExist() {
        byte[] bytes = new byte[]{10, 20};

        service.store("new-game", "img-1", bytes);

        Path dir = tempDir.resolve("new-game");
        assertTrue(Files.isDirectory(dir), "Subdirectory should have been created: " + dir);
    }

    // ---- deleteGame ----

    @Test
    void deleteGame_DeletesDirectoryAndAllContents() throws IOException {
        Path gameDir = tempDir.resolve("game-to-delete");
        Files.createDirectories(gameDir);
        Files.write(gameDir.resolve("img-1.png"), new byte[]{1, 2, 3});
        Files.write(gameDir.resolve("img-2.png"), new byte[]{4, 5, 6});

        service.deleteGame("game-to-delete");

        assertFalse(Files.exists(gameDir), "Directory should have been deleted");
    }

    @Test
    void deleteGame_Idempotent_DoesNotThrowWhenDirectoryAbsent() {
        assertDoesNotThrow(() -> service.deleteGame("nonexistent-game"));
    }
}
