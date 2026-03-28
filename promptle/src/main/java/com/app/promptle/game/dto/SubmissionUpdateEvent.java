package com.app.promptle.game.dto;

/**
 * WebSocket outbound event — fully implemented in Chunk 8.
 */
public record SubmissionUpdateEvent(int submittedCount, int totalCount) {}
