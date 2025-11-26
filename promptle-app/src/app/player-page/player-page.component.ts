import {Component, signal} from '@angular/core';
import {PLAYER_ICONS} from '../data/player/player-icons';

@Component({
  selector: 'app-player-page',
  imports: [],
  templateUrl: './player-page.component.html',
  styleUrl: './player-page.component.scss'
})
export class PlayerPageComponent {
  readonly username = signal('');
  readonly selectedIcon = signal('player-icons/saxon.svg');

  handleUsernameInput(value: string): void {
    this.username.set(value);
  }

  randomizeIcon(): void {
    if (PLAYER_ICONS.length <= 1) {
      return;
    }

    const currentIcon = this.selectedIcon();
    let nextIcon = currentIcon;

    while (nextIcon === currentIcon) {
      nextIcon = this.pickRandomIcon();
    }

    this.selectedIcon.set(nextIcon);
  }

  private pickRandomIcon(): string {
    const randomIndex = Math.floor(Math.random() * PLAYER_ICONS.length);
    return PLAYER_ICONS[randomIndex];
  }

  usernameIsValid(): boolean {
    return this.username().trim().length > 0;
  }

}
