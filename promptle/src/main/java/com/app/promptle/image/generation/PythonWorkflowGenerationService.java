package com.app.promptle.image.generation;

import com.app.promptle.image.api.ImageGenerationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Stub — not yet implemented. Throws UnsupportedOperationException.
 * Active when image.generation.provider=python.
 */
@Service
@ConditionalOnProperty(name = "image.generation.provider", havingValue = "python")
public class PythonWorkflowGenerationService implements ImageGenerationService {

    public PythonWorkflowGenerationService() {}

    @Override
    public CompletableFuture<String> generateImage(String prompt) {
        throw new UnsupportedOperationException("PythonWorkflowGenerationService not yet implemented");
    }
}
