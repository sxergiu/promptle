package com.app.promptle.image.api;

import java.util.concurrent.CompletableFuture;

/**
 * Generates images from prompts: text-to-image and (optionally) image-to-image.
 */
public interface ImageGenerationService {

    /**
     * Generates an image asynchronously based on the given prompt (text-to-image).
     *
     * @param prompt the text prompt
     * @return a CompletableFuture that resolves to the image URL
     */
    CompletableFuture<String> generateImage(String prompt);

    /**
     * Generates an image from a prompt seeded by a previous image (image-to-image).
     * Providers without img2img support degrade gracefully to plain {@link #generateImage}.
     *
     * @param prompt             the text prompt
     * @param previousImageBytes raw bytes of the image to evolve from
     * @return a CompletableFuture that resolves to the image URL
     */
    default CompletableFuture<String> generateImageFromImage(String prompt, byte[] previousImageBytes) {
        return generateImage(prompt);
    }
}
