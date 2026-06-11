import { GamePhase } from './game-phase.enum';
import { PlayerDto } from './player.model';

export interface JoinRoomResponse {
  playerToken: string;
  roomCode: string;
  playerId: string;
}

export interface RoomStateResponse {
  roomCode: string;
  phase: GamePhase;
  currentRound: number;
  totalRounds: number;
  players: PlayerDto[];
  hostId: string;
}

export interface GameStateSnapshot {
  phase: GamePhase;
  currentRound: number;
  totalRounds: number;
  timerSeconds: number;
  serverTimestamp: number;
  imageUrl: string | null;
  players: PlayerDto[];
  hasSubmitted: boolean;
  submittedCount: number;
  hostId: string;
}

export interface RoomEvent {
  type:
    | 'PLAYER_JOINED'
    | 'PLAYER_LEFT'
    | 'HOST_CHANGED'
    | 'GAME_STARTED'
    | 'GAME_RESET'
    | 'PLAYER_RETURNED';
  players: PlayerDto[];
  hostId: string;
}

export interface PhaseChangedEvent {
  phase: GamePhase;
  round: number;
  totalRounds: number;
  timerSeconds: number;
  serverTimestamp: number;
}

export interface SubmissionUpdateEvent {
  submittedCount: number;
  totalCount: number;
}

export interface RoundReadyPayload {
  round: number;
  imageUrl: string;
}

export interface ChainEntryDto {
  playerId: string | null;
  avatarId: string | null;
  text: string;
  imageUrl: string | null;
  isPlaceholder: boolean;
}

export interface ChainDto {
  entries: ChainEntryDto[];
}

export interface GameResultsEvent {
  chains: ChainDto[];
}

export interface ShowcaseAdvancedEvent {
  chainIndex: number;
}
