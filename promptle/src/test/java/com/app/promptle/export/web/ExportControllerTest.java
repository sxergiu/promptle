package com.app.promptle.export.web;

import com.app.promptle.export.dto.ExportRequest;
import com.app.promptle.export.service.ExportService;
import com.app.promptle.game.dto.ChainDto;
import com.app.promptle.game.dto.ChainEntryDto;
import com.app.promptle.game.model.GamePhase;
import com.app.promptle.room.dto.PlayerDto;
import com.app.promptle.room.model.Player;
import com.app.promptle.room.model.Room;
import com.app.promptle.room.repository.PlayerRepository;
import com.app.promptle.room.repository.RoomRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ExportController.class)
class ExportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ExportService exportService;

    @MockBean
    private PlayerRepository playerRepository;

    @MockBean
    private RoomRepository roomRepository;

    // ---- helpers ----

    private ExportRequest createValidExportRequest() {
        List<ChainEntryDto> entries = List.of(
                new ChainEntryDto("player-1", "icon-1", "A sunset over the ocean", null, false),
                new ChainEntryDto("player-2", "icon-2", null, "http://img/1.png", false),
                new ChainEntryDto("player-3", "icon-3", "Golden light on waves", null, false)
        );
        List<PlayerDto> players = List.of(
                new PlayerDto("player-1", "Alice", "icon-1", true),
                new PlayerDto("player-2", "Bob", "icon-2", true),
                new PlayerDto("player-3", "Carol", "icon-3", true)
        );
        return new ExportRequest(new ChainDto(entries), players);
    }

    private Player createPlayerInRoom(UUID token, String roomCode, GamePhase phase) {
        Room room = new Room();
        room.setId(UUID.randomUUID());
        room.setRoomCode(roomCode);
        room.setPhase(phase);

        Player player = new Player();
        player.setId(UUID.randomUUID());
        player.setToken(token);
        player.setAlias("Alice");
        player.setAvatarId("icon-1");
        player.setRoom(room);

        return player;
    }

    private void setupValidAuth(UUID token, String roomCode) {
        Player player = createPlayerInRoom(token, roomCode, GamePhase.RESULTS);
        when(playerRepository.findByToken(token)).thenReturn(Optional.of(player));
        when(roomRepository.findByRoomCode(roomCode)).thenReturn(Optional.of(player.getRoom()));
    }

    // ---- tests ----

    @Test
    void exportChain_ValidRequest_Returns200WithGifContentType() throws Exception {
        UUID token = UUID.randomUUID();
        String roomCode = "ABCD1234";
        setupValidAuth(token, roomCode);
        when(exportService.exportChain(any(ExportRequest.class), eq(roomCode)))
                .thenReturn(new byte[]{0x47, 0x49, 0x46, 0x38, 0x39, 0x61});

        mockMvc.perform(post("/api/export/{roomCode}", roomCode)
                        .header("X-Player-Token", token.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createValidExportRequest())))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/gif"));
    }

    @Test
    void exportChain_ValidRequest_ReturnsContentDispositionFilename() throws Exception {
        UUID token = UUID.randomUUID();
        String roomCode = "ABCD1234";
        setupValidAuth(token, roomCode);
        when(exportService.exportChain(any(ExportRequest.class), eq(roomCode)))
                .thenReturn(new byte[]{0x47, 0x49, 0x46});

        mockMvc.perform(post("/api/export/{roomCode}", roomCode)
                        .header("X-Player-Token", token.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createValidExportRequest())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"promptle-chain.gif\""));
    }

    @Test
    void exportChain_MissingPlayerToken_Returns400() throws Exception {
        String roomCode = "ABCD1234";
        when(roomRepository.findByRoomCode(roomCode)).thenReturn(Optional.of(new Room()));

        mockMvc.perform(post("/api/export/{roomCode}", roomCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createValidExportRequest())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportChain_InvalidPlayerToken_Returns403() throws Exception {
        UUID token = UUID.randomUUID();
        String roomCode = "ABCD1234";
        when(playerRepository.findByToken(token)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/export/{roomCode}", roomCode)
                        .header("X-Player-Token", token.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createValidExportRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void exportChain_PlayerNotInRoom_Returns403() throws Exception {
        UUID token = UUID.randomUUID();
        String roomCode = "ABCD1234";
        Player player = createPlayerInRoom(token, "OTHER999", GamePhase.RESULTS);
        when(playerRepository.findByToken(token)).thenReturn(Optional.of(player));

        // The requested room exists but is a different room than the player's
        Room requestedRoom = new Room();
        requestedRoom.setId(UUID.randomUUID());
        requestedRoom.setRoomCode(roomCode);
        requestedRoom.setPhase(GamePhase.RESULTS);
        when(roomRepository.findByRoomCode(roomCode)).thenReturn(Optional.of(requestedRoom));

        mockMvc.perform(post("/api/export/{roomCode}", roomCode)
                        .header("X-Player-Token", token.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createValidExportRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void exportChain_RoomNotFound_Returns404() throws Exception {
        UUID token = UUID.randomUUID();
        String roomCode = "NOROOM00";
        Player player = createPlayerInRoom(token, roomCode, GamePhase.RESULTS);
        when(playerRepository.findByToken(token)).thenReturn(Optional.of(player));
        when(roomRepository.findByRoomCode(roomCode)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/export/{roomCode}", roomCode)
                        .header("X-Player-Token", token.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createValidExportRequest())))
                .andExpect(status().isNotFound());
    }

    @Test
    void exportChain_RoomNotInResultsPhase_Returns409() throws Exception {
        UUID token = UUID.randomUUID();
        String roomCode = "ABCD1234";
        Player player = createPlayerInRoom(token, roomCode, GamePhase.LOBBY);
        when(playerRepository.findByToken(token)).thenReturn(Optional.of(player));
        when(roomRepository.findByRoomCode(roomCode)).thenReturn(Optional.of(player.getRoom()));

        mockMvc.perform(post("/api/export/{roomCode}", roomCode)
                        .header("X-Player-Token", token.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createValidExportRequest())))
                .andExpect(status().isConflict());
    }

    @Test
    void exportChain_EmptyChainEntries_Returns400() throws Exception {
        UUID token = UUID.randomUUID();
        String roomCode = "ABCD1234";
        setupValidAuth(token, roomCode);

        ExportRequest request = new ExportRequest(
                new ChainDto(List.of()),
                List.of(new PlayerDto("p1", "Alice", "icon-1", true))
        );

        mockMvc.perform(post("/api/export/{roomCode}", roomCode)
                        .header("X-Player-Token", token.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportChain_NullChainInBody_Returns400() throws Exception {
        UUID token = UUID.randomUUID();
        String roomCode = "ABCD1234";
        setupValidAuth(token, roomCode);

        String body = "{\"chain\":null,\"players\":[{\"id\":\"p1\",\"alias\":\"Alice\",\"avatarId\":\"icon-1\",\"connected\":true}]}";

        mockMvc.perform(post("/api/export/{roomCode}", roomCode)
                        .header("X-Player-Token", token.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportChain_ExportAlreadyInProgress_Returns429() throws Exception {
        UUID token = UUID.randomUUID();
        String roomCode = "ABCD1234";
        Player player = createPlayerInRoom(token, roomCode, GamePhase.RESULTS);
        when(playerRepository.findByToken(token)).thenReturn(Optional.of(player));
        when(roomRepository.findByRoomCode(roomCode)).thenReturn(Optional.of(player.getRoom()));

        // Pre-populate activeExports via reflection to simulate an in-progress export
        var field = ExportController.class.getDeclaredField("activeExports");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var activeExports = (java.util.concurrent.ConcurrentHashMap<UUID, Boolean>) field.get(
                // Get the controller bean from the application context
                mockMvc.getDispatcherServlet().getWebApplicationContext().getBean(ExportController.class));
        activeExports.put(player.getId(), Boolean.TRUE);

        try {
            mockMvc.perform(post("/api/export/{roomCode}", roomCode)
                            .header("X-Player-Token", token.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createValidExportRequest())))
                    .andExpect(status().isTooManyRequests());
        } finally {
            activeExports.remove(player.getId());
        }
    }

    @Test
    void exportChain_ServiceThrowsException_Returns500() throws Exception {
        UUID token = UUID.randomUUID();
        String roomCode = "ABCD1234";
        setupValidAuth(token, roomCode);
        when(exportService.exportChain(any(ExportRequest.class), eq(roomCode)))
                .thenThrow(new RuntimeException("Unexpected error"));

        mockMvc.perform(post("/api/export/{roomCode}", roomCode)
                        .header("X-Player-Token", token.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createValidExportRequest())))
                .andExpect(status().isInternalServerError());
    }
}
