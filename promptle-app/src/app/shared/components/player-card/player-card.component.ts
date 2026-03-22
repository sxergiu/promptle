import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { PLAYER_ICONS } from '../../../core/models/player-icons';

@Component({
  selector: 'app-player-card',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './player-card.component.html',
  styleUrl: './player-card.component.scss',
})
export class PlayerCardComponent {
  @Input() alias: string = '';
  @Input() avatarId: string = '';
  @Input() isHost: boolean = false;
  @Input() isCurrentPlayer: boolean = false;
  @Input() showKickButton: boolean = false;

  get avatarPath(): string | undefined {
    return PLAYER_ICONS.find((i) => i.id === this.avatarId)?.path;
  }
}
