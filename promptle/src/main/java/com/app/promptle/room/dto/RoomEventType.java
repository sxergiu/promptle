package com.app.promptle.room.dto;

/**
 * Event discriminator carried by {@link RoomEvent} on /topic/room/{roomCode}.
 * PLAYER_RETURNED (and JOIN/LEFT during RESULTS) carries the full game roster
 * with per-player returnedToLobby flags; the other events carry connected players only.
 */
public enum RoomEventType { PLAYER_JOINED, PLAYER_LEFT, HOST_CHANGED, GAME_STARTED, GAME_RESET, PLAYER_RETURNED }
