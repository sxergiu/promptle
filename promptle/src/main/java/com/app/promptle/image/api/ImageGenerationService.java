package com.app.promptle.image.api;

import java.util.concurrent.CompletableFuture;

/**
 * Stub interface — fully implemented in a later chunk.
 */
public interface ImageGenerationService {

    /**
     * Generates an image asynchronously based on the given prompt.
     *
     * @param prompt the text prompt
     * @return a CompletableFuture that resolves to the image URL
     */
    CompletableFuture<String> generateImage(String prompt);
}
