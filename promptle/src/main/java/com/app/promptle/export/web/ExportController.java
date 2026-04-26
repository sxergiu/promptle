package com.app.promptle.export.web;

import com.app.promptle.export.dto.ExportRequest;
import com.app.promptle.export.service.ExportService;
import com.app.promptle.game.model.GamePhase;
import com.app.promptle.room.model.Player;
import com.app.promptle.room.model.Room;
import com.app.promptle.room.repository.PlayerRepository;
import com.app.promptle.room.repository.RoomRepository;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
public class ExportController {

    private static final Logger log = LoggerFactory.getLogger(ExportController.class);

    private final ExportService exportService;
    private final RoomRepository roomRepository;
    private final PlayerRepository playerRepository;
    private final ConcurrentHashMap<UUID, Boolean> activeExports = new ConcurrentHashMap<>();

    public ExportController(ExportService exportService,
                            RoomRepository roomRepository,
                            PlayerRepository playerRepository) {
        this.exportService = exportService;
        this.roomRepository = roomRepository;
        this.playerRepository = playerRepository;
    }

    @PostMapping("/api/export/{roomCode}")
    public ResponseEntity<?> exportChain(
            @PathVariable String roomCode,
            @RequestHeader("X-Player-Token") String playerTokenHeader,
            @Valid @RequestBody ExportRequest request) {

        // Parse player token
        UUID playerToken;
        try {
            playerToken = UUID.fromString(playerTokenHeader);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Invalid player token"));
        }

        // Find player by token
        Player player = playerRepository.findByToken(playerToken).orElse(null);
        if (player == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Player not found"));
        }

        // Find room
        Room room = roomRepository.findByRoomCode(roomCode).orElse(null);
        if (room == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Room not found"));
        }

        // Verify player belongs to room
        if (player.getRoom() == null || !player.getRoom().getId().equals(room.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Player is not in this room"));
        }

        // Verify room is in RESULTS phase
        if (room.getPhase() != GamePhase.RESULTS) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Export is only available during the results phase"));
        }

        // Validate chain entries
        if (request.chain() == null || request.chain().entries() == null || request.chain().entries().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Chain entries must not be empty"));
        }

        // Rate limit: one export per player at a time
        if (activeExports.putIfAbsent(player.getId(), Boolean.TRUE) != null) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "An export is already in progress"));
        }

        try {
            byte[] gifBytes = exportService.exportChain(request, roomCode);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_GIF);
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"promptle-chain.gif\"");

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(gifBytes);

        } catch (Exception e) {
            log.error("Failed to export chain for room {}: {}", roomCode, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to generate export"));
        } finally {
            activeExports.remove(player.getId());
        }
    }
}
