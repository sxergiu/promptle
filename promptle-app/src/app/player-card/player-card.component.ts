import { Component, inject } from '@angular/core';
import { PlayerService } from '../service/player.service';

@Component({
  selector: 'app-player-card',
  imports: [],
  templateUrl: './player-card.component.html',
  styleUrl: './player-card.component.scss',
})
export class PlayerCardComponent {
  readonly player = inject(PlayerService);
}
