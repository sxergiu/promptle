package com.app.promptle.image.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class S3ImageStorageServiceTest {

    private S3ImageStorageService service;

    @BeforeEach
    void setUp() {
        service = new S3ImageStorageService();
    }

    @Test
    void store_ThrowsUnsupportedOperationException() {
        assertThrows(UnsupportedOperationException.class,
                () -> service.store("game-1", "img-1", new byte[]{1, 2, 3}));
    }

    @Test
    void deleteGame_ThrowsUnsupportedOperationException() {
        assertThrows(UnsupportedOperationException.class,
                () -> service.deleteGame("game-1"));
    }
}
