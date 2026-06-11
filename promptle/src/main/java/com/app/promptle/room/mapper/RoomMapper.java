package com.app.promptle.room.mapper;

import com.app.promptle.game.dto.GameStateSnapshot;
import com.app.promptle.room.dto.PlayerDto;
import com.app.promptle.room.dto.RoomStateResponse;
import com.app.promptle.room.model.Player;
import com.app.promptle.room.model.Room;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class RoomMapper {

    public PlayerDto toDto(Player player) {
        return toDto(player, Set.of());
    }

    public PlayerDto toDto(Player player, Set<UUID> returnedIds) {
        return new PlayerDto(
                player.getId().toString(),
                player.getAlias(),
                player.getAvatarId(),
                player.isConnected(),
                returnedIds.contains(player.getId())
        );
    }

    public RoomStateResponse toStateResponse(Room room, List<Player> players) {
        List<PlayerDto> playerDtos = players.stream().map(this::toDto).toList();
        return new RoomStateResponse(
                room.getRoomCode(),
                room.getPhase(),
                room.getCurrentRound(),
                room.getTotalRounds(),
                playerDtos,
                room.getHostId() != null ? room.getHostId().toString() : null
        );
    }

    /**
     * Builds a GameStateSnapshot with hasSubmitted = false.
     * RoomService replaces this with the real hasSubmitted value after calling this method.
     */
    public GameStateSnapshot toSnapshot(Room room, List<Player> players, long timerSeconds,
                                        long serverTimestamp, String imageUrl) {
        return toSnapshot(room, players, timerSeconds, serverTimestamp, imageUrl, Set.of());
    }

    public GameStateSnapshot toSnapshot(Room room, List<Player> players, long timerSeconds,
                                        long serverTimestamp, String imageUrl, Set<UUID> returnedIds) {
        List<PlayerDto> playerDtos = players.stream().map(p -> toDto(p, returnedIds)).toList();
        String hostId = room.getHostId() != null ? room.getHostId().toString() : null;
        return new GameStateSnapshot(
                room.getPhase(),
                room.getCurrentRound(),
                room.getTotalRounds(),
                timerSeconds,
                serverTimestamp,
                imageUrl,
                false,
                0,
                playerDtos,
                hostId
        );
    }
}
