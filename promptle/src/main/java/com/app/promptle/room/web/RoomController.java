package com.app.promptle.room.web;

import com.app.promptle.common.exception.GameException;
import com.app.promptle.game.dto.GameStateSnapshot;
import com.app.promptle.game.service.GameService;
import com.app.promptle.room.dto.CreateRoomRequest;
import com.app.promptle.room.dto.JoinRoomRequest;
import com.app.promptle.room.dto.JoinRoomResponse;
import com.app.promptle.room.model.Player;
import com.app.promptle.room.repository.PlayerRepository;
import com.app.promptle.room.service.RoomService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService roomService;
    private final PlayerRepository playerRepository;
    private final GameService gameService;

    public RoomController(RoomService roomService,
                          PlayerRepository playerRepository,
                          GameService gameService) {
        this.roomService = roomService;
        this.playerRepository = playerRepository;
        this.gameService = gameService;
    }

    @PostMapping
    public ResponseEntity<JoinRoomResponse> createRoom(@Valid @RequestBody CreateRoomRequest request) {
        return ResponseEntity.ok(roomService.createRoom(request));
    }

    @PostMapping("/{roomCode}/join")
    public ResponseEntity<JoinRoomResponse> joinRoom(@PathVariable String roomCode,
                                                      @Valid @RequestBody JoinRoomRequest request) {
        JoinRoomResponse response = roomService.joinRoom(roomCode, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{roomCode}/state")
    public ResponseEntity<GameStateSnapshot> getState(@PathVariable String roomCode,
                                                       @RequestParam String token) {
        GameStateSnapshot snapshot = roomService.getGameStateSnapshot(roomCode, token);
        return ResponseEntity.ok(snapshot);
    }

    @PostMapping("/{roomCode}/start")
    public ResponseEntity<Void> startGame(@PathVariable String roomCode,
                                           @RequestParam String token) {
        UUID tokenUuid = UUID.fromString(token);
        Player player = playerRepository.findByToken(tokenUuid)
                .orElseThrow(() -> new GameException("Player not found for token: " + token));
        gameService.startGame(roomCode, player.getId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{roomCode}/reset")
    public ResponseEntity<Void> resetGame(@PathVariable String roomCode,
                                           @RequestParam String token) {
        roomService.resetGame(roomCode, token);
        return ResponseEntity.ok().build();
    }
}
