import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { RoomApiService } from '../../core/services/room-api.service';
import { PlayerService } from '../../core/services/player.service';
import { PlayerIcon, PLAYER_ICONS } from '../../core/models/player-icons';

@Component({
  selector: 'app-join',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './join.component.html',
  styleUrl: './join.component.scss',
})
export class JoinComponent implements OnInit {
  roomCode: string = '';
  alias = signal('');
  errorMessage: string | null = null;

  private _selectedIcon = signal<PlayerIcon>(PLAYER_ICONS[0]);

  get selectedIcon(): PlayerIcon {
    return this._selectedIcon();
  }

  constructor(
    private roomApiService: RoomApiService,
    private playerService: PlayerService,
    private router: Router,
    private route: ActivatedRoute
  ) {}

  ngOnInit(): void {
    this._selectedIcon.set(PLAYER_ICONS[Math.floor(Math.random() * PLAYER_ICONS.length)]);
    this.roomCode = this.route.snapshot.paramMap.get('roomCode') ?? '';
    if (this.playerService.loadFromLocalStorage(this.roomCode)) {
      this.router.navigate(['/lobby', this.roomCode]);
      return;
    }
  }

  shuffleIcon(): void {
    if (PLAYER_ICONS.length <= 1) return;
    const current = this._selectedIcon();
    let next = current;
    while (next === current) {
      next = PLAYER_ICONS[Math.floor(Math.random() * PLAYER_ICONS.length)];
    }
    this._selectedIcon.set(next);
  }

  onJoinRoom(): void {
    if (!this.alias().trim()) return;
    const icon = this._selectedIcon();
    this.errorMessage = null;
    this.roomApiService.joinRoom(this.roomCode, this.alias(), icon.id).subscribe({
      next: (response) => {
        this.playerService.saveToLocalStorage(response.roomCode, {
          playerToken: response.playerToken,
          playerId: response.playerId,
          alias: this.alias(),
          avatarId: icon.id,
        });
        this.router.navigate(['/lobby', response.roomCode]);
      },
      error: (error) => {
        const message: string = error?.error?.error ?? '';
        if (message.toLowerCase().includes('full')) {
          this.errorMessage = 'Room is full';
        } else if (message.toLowerCase().includes('in progress')) {
          this.errorMessage = 'Game already in progress';
        } else {
          this.errorMessage = 'An error occurred. Please try again.';
        }
      },
    });
  }
}
