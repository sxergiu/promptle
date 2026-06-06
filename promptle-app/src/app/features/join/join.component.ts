import { Component, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';

import { RoomApiService } from '../../core/services/room-api.service';
import { PlayerService } from '../../core/services/player.service';
import { PlayerSetupComponent, PlayerSetupSubmit } from '../../shared/components/player-setup/player-setup.component';

@Component({
  selector: 'app-join',
  standalone: true,
  imports: [PlayerSetupComponent],
  templateUrl: './join.component.html',
  styleUrl: './join.component.scss',
})
export class JoinComponent implements OnInit {
  roomCode = '';
  readonly errorMessage = signal<string | null>(null);
  readonly busy = signal(false);

  constructor(
    private roomApiService: RoomApiService,
    private playerService: PlayerService,
    private router: Router,
    private route: ActivatedRoute,
  ) {}

  ngOnInit(): void {
    this.roomCode = this.route.snapshot.paramMap.get('roomCode') ?? '';
    if (this.playerService.loadFromLocalStorage(this.roomCode)) {
      this.router.navigate(['/lobby', this.roomCode]);
    }
  }

  onJoinRoom({ alias, avatarId }: PlayerSetupSubmit): void {
    this.errorMessage.set(null);
    this.busy.set(true);
    this.roomApiService.joinRoom(this.roomCode, alias, avatarId).subscribe({
      next: (response) => {
        this.playerService.saveToLocalStorage(response.roomCode, {
          playerToken: response.playerToken,
          playerId: response.playerId,
          alias,
          avatarId,
        });
        this.router.navigate(['/lobby', response.roomCode]);
      },
      error: (error) => {
        this.busy.set(false);
        const message: string = error?.error?.error ?? '';
        if (message.toLowerCase().includes('full')) {
          this.errorMessage.set('Room is full');
        } else if (message.toLowerCase().includes('in progress')) {
          this.errorMessage.set('Game already in progress');
        } else {
          this.errorMessage.set('An error occurred. Please try again.');
        }
      },
    });
  }
}
