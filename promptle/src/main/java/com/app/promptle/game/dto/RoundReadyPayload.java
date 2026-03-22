package com.app.promptle.game.dto;

/**
 * Per-player WebSocket payload — fully implemented in Chunk 9.
 */
public record RoundReadyPayload(int round, String imageUrl) {}
