package com.app.promptle.room.mapper;

import com.app.promptle.game.model.GamePhase;
import com.app.promptle.game.dto.GameStateSnapshot;
import com.app.promptle.room.dto.PlayerDto;
import com.app.promptle.room.dto.RoomStateResponse;
import com.app.promptle.room.model.Player;
import com.app.promptle.room.model.Room;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RoomMapperTest {

    private RoomMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new RoomMapper();
    }

    // ---- toDto(Player) ----

    @Test
    void toDto_MapsAllFieldsCorrectly() {
        Player player = new Player();
        UUID id = UUID.randomUUID();
        player.setId(id);
        player.setAlias("TestAlias");
        player.setAvatarId("icon-3");
        player.setConnected(true);

        PlayerDto dto = mapper.toDto(player);

        assertEquals(id.toString(), dto.id());
        assertEquals("TestAlias", dto.alias());
        assertEquals("icon-3", dto.avatarId());
        assertTrue(dto.connected());
    }

    // ---- toStateResponse(Room, List<Player>) ----

    @Test
    void toStateResponse_MapsAllTopLevelFieldsAndPlayerList() {
        UUID hostId = UUID.randomUUID();
        Room room = new Room();
        room.setRoomCode("ABCD1234");
        room.setPhase(GamePhase.LOBBY);
        room.setCurrentRound(0);
        room.setTotalRounds(4);
        room.setHostId(hostId);

        Player p1 = new Player();
        p1.setId(UUID.randomUUID());
        p1.setAlias("Alice");
        p1.setAvatarId("icon-1");
        p1.setConnected(true);

        Player p2 = new Player();
        p2.setId(UUID.randomUUID());
        p2.setAlias("Bob");
        p2.setAvatarId("icon-2");
        p2.setConnected(false);

        RoomStateResponse response = mapper.toStateResponse(room, List.of(p1, p2));

        assertEquals("ABCD1234", response.roomCode());
        assertEquals(GamePhase.LOBBY, response.phase());
        assertEquals(0, response.currentRound());
        assertEquals(4, response.totalRounds());
        assertEquals(hostId.toString(), response.hostId());
        assertEquals(2, response.players().size());
        assertEquals("Alice", response.players().get(0).alias());
        assertEquals("Bob", response.players().get(1).alias());
    }

    // ---- toSnapshot(Room, List<Player>, long timerSeconds, long serverTimestamp, String imageUrl) ----

    @Test
    void toSnapshot_NullImageUrl_ResultsInNullImageUrlField() {
        Room room = new Room();
        room.setRoomCode("ROOM0001");
        room.setPhase(GamePhase.PROMPTING);
        room.setCurrentRound(1);
        room.setTotalRounds(3);
        room.setHostId(UUID.randomUUID());

        GameStateSnapshot snapshot = mapper.toSnapshot(room, List.of(), 60L, 1700000000000L, null);

        assertNull(snapshot.imageUrl());
    }

    @Test
    void toSnapshot_NonNullImageUrl_EqualToSuppliedString() {
        Room room = new Room();
        room.setRoomCode("ROOM0002");
        room.setPhase(GamePhase.GUESSING);
        room.setCurrentRound(2);
        room.setTotalRounds(3);
        room.setHostId(UUID.randomUUID());

        GameStateSnapshot snapshot = mapper.toSnapshot(room, List.of(), 60L, 1700000000000L, "/api/images/game1/img1");

        assertEquals("/api/images/game1/img1", snapshot.imageUrl());
    }

    @Test
    void toSnapshot_TimerFieldsPreservedUnchanged() {
        Room room = new Room();
        room.setRoomCode("ROOM0003");
        room.setPhase(GamePhase.PROMPTING);
        room.setCurrentRound(1);
        room.setTotalRounds(2);
        room.setHostId(UUID.randomUUID());

        GameStateSnapshot snapshot = mapper.toSnapshot(room, List.of(), 60L, 1700000000000L, null);

        assertEquals(60L, snapshot.timerSeconds());
        assertEquals(1700000000000L, snapshot.serverTimestamp());
    }

    @Test
    void toSnapshot_includesAllFields() {
        UUID hostId = UUID.randomUUID();
        Room room = new Room();
        room.setRoomCode("ROOM0004");
        room.setPhase(GamePhase.GUESSING);
        room.setCurrentRound(2);
        room.setTotalRounds(4);
        room.setHostId(hostId);

        Player p = new Player();
        p.setId(UUID.randomUUID());
        p.setAlias("Carol");
        p.setAvatarId("icon-5");
        p.setConnected(true);

        GameStateSnapshot snapshot = mapper.toSnapshot(room, List.of(p), 45L, 1700000001000L, "/api/images/game/img2");

        assertEquals(GamePhase.GUESSING, snapshot.phase());
        assertEquals(2, snapshot.currentRound());
        assertEquals(4, snapshot.totalRounds());
        assertEquals(45L, snapshot.timerSeconds());
        assertEquals(1700000001000L, snapshot.serverTimestamp());
        assertEquals("/api/images/game/img2", snapshot.imageUrl());
        assertFalse(snapshot.hasSubmitted());
        assertEquals(0, snapshot.submittedCount());
        assertEquals(hostId.toString(), snapshot.hostId());
        assertEquals(1, snapshot.players().size());
        assertEquals("Carol", snapshot.players().get(0).alias());
    }
}
