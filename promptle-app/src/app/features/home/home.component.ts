import { Component } from '@angular/core';
import { Router } from '@angular/router';

import { RoomApiService } from '../../core/services/room-api.service';
import { PlayerService } from '../../core/services/player.service';
import { PlayerSetupComponent, PlayerSetupSubmit } from '../../shared/components/player-setup/player-setup.component';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [PlayerSetupComponent],
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss',
})
export class HomeComponent {
  constructor(
    private roomApiService: RoomApiService,
    private playerService: PlayerService,
    private router: Router,
  ) {}

  onCreateRoom({ alias, avatarId }: PlayerSetupSubmit): void {
    this.roomApiService.createRoom(alias, avatarId).subscribe({
      next: (response) => {
        this.playerService.saveToLocalStorage(response.roomCode, {
          playerToken: response.playerToken,
          playerId: response.playerId,
          alias,
          avatarId,
        });
        this.router.navigate(['/lobby', response.roomCode]);
      },
    });
  }
}
