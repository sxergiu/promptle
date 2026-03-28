package com.app.promptle.game.event;

/**
 * Internal Spring application event published when all images for a generation
 * phase have been stored. Triggers the transition from GENERATING to GUESSING.
 */
public record AllImagesReadyApplicationEvent(String roomCode) {}
