import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { RoomApiService } from '../../core/services/room-api.service';
import { PlayerService } from '../../core/services/player.service';
import { PlayerIcon, PLAYER_ICONS } from '../../core/models/player-icons';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [CommonModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss',
})
export class HomeComponent implements OnInit {
  alias = signal('');

  private _selectedIcon = signal<PlayerIcon>(PLAYER_ICONS[0]);

  get selectedIcon(): PlayerIcon {
    return this._selectedIcon();
  }

  constructor(
    private roomApiService: RoomApiService,
    private playerService: PlayerService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this._selectedIcon.set(PLAYER_ICONS[Math.floor(Math.random() * PLAYER_ICONS.length)]);
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

  onCreateRoom(): void {
    if (!this.alias().trim()) return;
    const icon = this._selectedIcon();
    this.roomApiService.createRoom(this.alias(), icon.id).subscribe({
      next: (response) => {
        this.playerService.saveToLocalStorage(response.roomCode, {
          playerToken: response.playerToken,
          playerId: response.playerId,
          alias: this.alias(),
          avatarId: icon.id,
        });
        this.router.navigate(['/lobby', response.roomCode]);
      },
    });
  }
}
