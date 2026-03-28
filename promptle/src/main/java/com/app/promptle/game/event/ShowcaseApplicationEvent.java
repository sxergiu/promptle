package com.app.promptle.game.event;

/**
 * Internal Spring application event — fully implemented in Chunk 12.
 */
public record ShowcaseApplicationEvent(String roomCode, int chainIndex) {}
