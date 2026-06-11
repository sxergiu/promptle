import { Component, HostBinding, Input } from '@angular/core';
import { PLAYER_ICONS } from '../../../core/models/player-icons';

@Component({
  selector: 'app-player-card',
  standalone: true,
  imports: [],
  templateUrl: './player-card.component.html',
  styleUrl: './player-card.component.scss',
})
export class PlayerCardComponent {
  @Input() alias: string = '';
  @Input() avatarId: string = '';
  @Input() isHost: boolean = false;
  @Input() isCurrentPlayer: boolean = false;
  @Input() showKickButton: boolean = false;
  @Input() empty: boolean = false;
  @Input() size: 'default' | 'large' = 'default';
  /** Return-from-results status: true = returned, false = still away, null = neutral. */
  @Input() returned: boolean | null = null;

  @HostBinding('class.size-large') get isLarge() { return this.size === 'large'; }
  @HostBinding('class.empty') get isEmpty() { return this.empty; }
  @HostBinding('class.is-me') get isMe() { return this.isCurrentPlayer; }
  @HostBinding('class.is-returned') get isReturned() { return this.returned === true; }
  @HostBinding('class.is-away') get isAway() { return this.returned === false; }

  get avatarPath(): string | undefined {
    return PLAYER_ICONS.find((i) => i.id === this.avatarId)?.path;
  }
}
