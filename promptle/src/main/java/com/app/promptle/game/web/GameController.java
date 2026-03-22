package com.app.promptle.game.web;

import com.app.promptle.game.dto.SubmissionPayload;
import com.app.promptle.game.service.GameService;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

/**
 * Stub — fully implemented in a later chunk.
 * Handles WebSocket messages for game submissions.
 */
@Controller
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    public void handlePrompt(String roomCode, SubmissionPayload payload, Principal principal) {
        UUID playerId = UUID.fromString(principal.getName());
        gameService.submitPrompt(roomCode, playerId, payload.text());
    }

    public void handleGuess(String roomCode, SubmissionPayload payload, Principal principal) {
        UUID playerId = UUID.fromString(principal.getName());
        gameService.submitGuess(roomCode, playerId, payload.text());
    }

    public void handleNextChain(String roomCode, Principal principal) {
        UUID playerId = UUID.fromString(principal.getName());
        gameService.advanceShowcase(roomCode, playerId);
    }
}
