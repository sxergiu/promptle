import { Injectable, signal } from '@angular/core';
import { PLAYER_ICONS } from '../data/player/player-icons';

@Injectable({ providedIn: 'root' })
export class PlayerService {
  readonly username = signal('');
  readonly selectedIcon = signal(PLAYER_ICONS[0]);

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
}
