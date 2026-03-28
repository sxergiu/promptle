package com.app.promptle.game.event;

/**
 * Internal Spring application event — fully implemented in Chunk 8.
 */
public record SubmissionUpdateApplicationEvent(String roomCode, int submittedCount, int totalCount) {}
