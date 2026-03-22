import { Injectable, signal } from '@angular/core';
import { PlayerIcon, PLAYER_ICONS } from '../models/player-icons';

export interface PlayerStorageData {
  playerToken: string;
  playerId: string;
  alias: string;
  avatarId: string;
}

@Injectable({ providedIn: 'root' })
export class PlayerService {
  readonly username = signal('');
  readonly selectedIcon = signal<PlayerIcon>(PLAYER_ICONS[0]);
  readonly playerToken = signal<string | null>(null);
  readonly playerId = signal<string | null>(null);

  setUsername(value: string): void {
    this.username.set(value);
  }

  randomizeIcon(): void {
    if (PLAYER_ICONS.length <= 1) return;
    const current = this.selectedIcon();
    let next = current;
    while (next === current) {
      next = PLAYER_ICONS[Math.floor(Math.random() * PLAYER_ICONS.length)];
    }
    this.selectedIcon.set(next);
  }

  isValid(): boolean {
    return this.username().trim().length > 0;
  }

  saveToLocalStorage(roomCode: string, data: PlayerStorageData): void {
    localStorage.setItem(`promptle_player_${roomCode}`, JSON.stringify(data));
    this.playerToken.set(data.playerToken);
    this.playerId.set(data.playerId);
  }

  loadFromLocalStorage(roomCode: string): PlayerStorageData | null {
    const raw = localStorage.getItem(`promptle_player_${roomCode}`);
    if (!raw) {
      return null;
    }
    const parsed = JSON.parse(raw) as PlayerStorageData;
    this.playerToken.set(parsed.playerToken);
    this.playerId.set(parsed.playerId);
    return parsed;
  }

  clearLocalStorage(roomCode: string): void {
    localStorage.removeItem(`promptle_player_${roomCode}`);
  }
}
