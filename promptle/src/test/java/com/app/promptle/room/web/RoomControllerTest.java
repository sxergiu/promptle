package com.app.promptle.room.web;

import com.app.promptle.common.exception.GameException;
import com.app.promptle.game.dto.GameStateSnapshot;
import com.app.promptle.game.model.GamePhase;
import com.app.promptle.game.service.GameService;
import com.app.promptle.room.dto.JoinRoomResponse;
import com.app.promptle.room.repository.PlayerRepository;
import com.app.promptle.room.service.RoomService;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RoomController.class)
class RoomControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RoomService roomService;

    @MockBean
    private PlayerRepository playerRepository;

    @MockBean
    private GameService gameService;

    // ---- POST /api/rooms ----

    @Test
    void createRoom_ValidRequest_Returns200WithRoomCode() throws Exception {
        when(roomService.createRoom(any())).thenReturn(new JoinRoomResponse("token-xyz", "A1B2C3D4", UUID.randomUUID().toString()));

        mockMvc.perform(post("/api/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"Alice\",\"avatarId\":\"icon-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomCode").value("A1B2C3D4"));
    }

    @Test
    void createRoom_BlankAlias_Returns400() throws Exception {
        mockMvc.perform(post("/api/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"\",\"avatarId\":\"icon-1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRoom_BlankAvatarId_Returns400() throws Exception {
        mockMvc.perform(post("/api/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"Alice\",\"avatarId\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- POST /api/rooms/{roomCode}/join ----

    @Test
    void joinRoom_ValidRequest_Returns200WithAllFields() throws Exception {
        JoinRoomResponse response = new JoinRoomResponse("token-abc", "ABCD1234", UUID.randomUUID().toString());
        when(roomService.joinRoom(eq("ABCD1234"), any())).thenReturn(response);

        mockMvc.perform(post("/api/rooms/ABCD1234/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"Bob\",\"avatarId\":\"icon-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerToken").value("token-abc"))
                .andExpect(jsonPath("$.roomCode").value("ABCD1234"))
                .andExpect(jsonPath("$.playerId").exists());
    }

    @Test
    void joinRoom_RoomFull_Returns409WithErrorBody() throws Exception {
        when(roomService.joinRoom(eq("ABCD1234"), any()))
                .thenThrow(new GameException("Room is full"));

        mockMvc.perform(post("/api/rooms/ABCD1234/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"Bob\",\"avatarId\":\"icon-2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void joinRoom_GameInProgress_Returns409WithErrorBody() throws Exception {
        when(roomService.joinRoom(eq("ABCD1234"), any()))
                .thenThrow(new GameException("Game already in progress"));

        mockMvc.perform(post("/api/rooms/ABCD1234/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"Bob\",\"avatarId\":\"icon-2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void joinRoom_InvalidBody_Returns400() throws Exception {
        mockMvc.perform(post("/api/rooms/ABCD1234/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"\",\"avatarId\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- GET /api/rooms/{roomCode}/state ----

    @Test
    void getState_ValidToken_Returns200WithSnapshot() throws Exception {
        GameStateSnapshot snapshot = new GameStateSnapshot(
                GamePhase.PROMPTING, 1, 4, 60L, 1700000000000L, null, false, 0, List.of(), "host-id");
        when(roomService.getGameStateSnapshot(eq("ABCD1234"), anyString())).thenReturn(snapshot);

        mockMvc.perform(get("/api/rooms/ABCD1234/state")
                        .param("token", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
    }

    @Test
    void getState_TokenNotFound_Returns409() throws Exception {
        when(roomService.getGameStateSnapshot(eq("ABCD1234"), anyString()))
                .thenThrow(new GameException("Token not found"));

        mockMvc.perform(get("/api/rooms/ABCD1234/state")
                        .param("token", UUID.randomUUID().toString()))
                .andExpect(status().isConflict());
    }

    // ---- POST /api/rooms/{roomCode}/start ----

    @Test
    void startGame_Success_Returns200() throws Exception {
        UUID playerId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        com.app.promptle.room.model.Player player = new com.app.promptle.room.model.Player();
        player.setId(playerId);
        player.setToken(token);

        when(playerRepository.findByToken(token)).thenReturn(Optional.of(player));
        doNothing().when(gameService).startGame(eq("ABCD1234"), eq(playerId));

        mockMvc.perform(post("/api/rooms/ABCD1234/start")
                        .param("token", token.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void startGame_NotHost_Returns409() throws Exception {
        UUID playerId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        com.app.promptle.room.model.Player player = new com.app.promptle.room.model.Player();
        player.setId(playerId);
        player.setToken(token);

        when(playerRepository.findByToken(token)).thenReturn(Optional.of(player));
        doThrow(new GameException("Not the host")).when(gameService).startGame(eq("ABCD1234"), eq(playerId));

        mockMvc.perform(post("/api/rooms/ABCD1234/start")
                        .param("token", token.toString()))
                .andExpect(status().isConflict());
    }
}
