import { Component, inject, Input } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { PlayerService } from '../service/player.service';

@Component({
  selector: 'app-player-page',
  imports: [NgTemplateOutlet],
  templateUrl: './player-page.component.html',
  styleUrl: './player-page.component.scss',
})
export class PlayerPageComponent {
  @Input() standalone = true;

  readonly player = inject(PlayerService);
}
