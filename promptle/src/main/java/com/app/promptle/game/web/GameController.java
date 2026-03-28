package com.app.promptle.game.web;

import com.app.promptle.game.dto.SubmissionPayload;
import com.app.promptle.game.service.GameService;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

@Controller
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @MessageMapping("/room/{roomCode}/prompt")
    public void handlePrompt(@DestinationVariable String roomCode,
                             @Payload SubmissionPayload payload,
                             Principal principal) {
        UUID playerId = UUID.fromString(principal.getName());
        gameService.submitPrompt(roomCode, playerId, payload.text());
    }

    @MessageMapping("/room/{roomCode}/guess")
    public void handleGuess(@DestinationVariable String roomCode,
                            @Payload SubmissionPayload payload,
                            Principal principal) {
        UUID playerId = UUID.fromString(principal.getName());
        gameService.submitGuess(roomCode, playerId, payload.text());
    }

    @MessageMapping("/room/{roomCode}/next-chain")
    public void handleNextChain(@DestinationVariable String roomCode, Principal principal) {
        UUID playerId = UUID.fromString(principal.getName());
        gameService.advanceShowcase(roomCode, playerId);
    }
}
