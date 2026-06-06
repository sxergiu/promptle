import { Component, EventEmitter, Input, OnInit, Output, signal } from '@angular/core';

import { PlayerIcon, PLAYER_ICONS } from '../../../core/models/player-icons';

export interface PlayerSetupSubmit {
  alias: string;
  avatarId: string;
}

/**
 * Shared "set up your player" screen used by both Home (create) and Join.
 * Owns the brand, avatar picker and name field; the parent supplies the
 * action by handling (play) and may pass an errorMessage / busy state.
 */
@Component({
  selector: 'app-player-setup',
  standalone: true,
  imports: [],
  templateUrl: './player-setup.component.html',
  styleUrl: './player-setup.component.scss',
})
export class PlayerSetupComponent implements OnInit {
  @Input() errorMessage: string | null = null;
  @Input() busy = false;
  @Output() readonly play = new EventEmitter<PlayerSetupSubmit>();

  readonly alias = signal('');
  readonly aliasRejected = signal(false);
  diceSpinning = false;

  private readonly _selectedIcon = signal<PlayerIcon>(PLAYER_ICONS[0]);

  get selectedIcon(): PlayerIcon {
    return this._selectedIcon();
  }

  ngOnInit(): void {
    this._selectedIcon.set(PLAYER_ICONS[Math.floor(Math.random() * PLAYER_ICONS.length)]);
  }

  shuffleIcon(): void {
    this.diceSpinning = false;
    if (PLAYER_ICONS.length <= 1) return;
    const current = this._selectedIcon();
    let next = current;
    while (next === current) {
      next = PLAYER_ICONS[Math.floor(Math.random() * PLAYER_ICONS.length)];
    }
    this._selectedIcon.set(next);
  }

  onAliasInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.value.length > 13) {
      input.value = input.value.slice(0, 13);
      this.aliasRejected.set(true);
      setTimeout(() => this.aliasRejected.set(false), 400);
    } else {
      this.aliasRejected.set(false);
    }
    this.alias.set(input.value);
  }

  onPlay(): void {
    const alias = this.alias().trim();
    if (!alias || this.busy) return;
    this.play.emit({ alias, avatarId: this._selectedIcon().id });
  }
}
