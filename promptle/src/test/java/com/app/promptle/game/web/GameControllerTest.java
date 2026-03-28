package com.app.promptle.game.web;

import com.app.promptle.game.dto.SubmissionPayload;
import com.app.promptle.game.service.GameService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.Principal;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GameControllerTest {

    @Mock
    private GameService gameService;

    private GameController controller;

    @BeforeEach
    void setUp() {
        controller = new GameController(gameService);
    }

    @Test
    void handlePrompt_DelegatesToGameServiceWithCorrectArguments() {
        UUID playerId = UUID.randomUUID();
        Principal principal = playerId::toString;
        SubmissionPayload payload = new SubmissionPayload("A beautiful lake");

        controller.handlePrompt("ABCD1234", payload, principal);

        verify(gameService).submitPrompt("ABCD1234", playerId, "A beautiful lake");
    }

    @Test
    void handleGuess_DelegatesToGameServiceWithCorrectArguments() {
        UUID playerId = UUID.randomUUID();
        Principal principal = playerId::toString;
        SubmissionPayload payload = new SubmissionPayload("A lake at sunset");

        controller.handleGuess("ABCD1234", payload, principal);

        verify(gameService).submitGuess("ABCD1234", playerId, "A lake at sunset");
    }

    @Test
    void handleNextChain_DelegatesToAdvanceShowcaseWithCorrectArguments() {
        UUID playerId = UUID.randomUUID();
        Principal principal = playerId::toString;

        controller.handleNextChain("ABCD1234", principal);

        verify(gameService).advanceShowcase("ABCD1234", playerId);
    }
}
