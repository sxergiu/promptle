package com.app.promptle.image.generation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PythonWorkflowGenerationServiceTest {

    private PythonWorkflowGenerationService service;

    @BeforeEach
    void setUp() {
        service = new PythonWorkflowGenerationService();
    }

    @Test
    void generateImage_ThrowsUnsupportedOperationException() {
        assertThrows(UnsupportedOperationException.class,
                () -> service.generateImage("any prompt"));
    }
}
