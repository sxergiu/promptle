import {Component, signal} from '@angular/core';

@Component({
  selector: 'app-player-page',
  imports: [],
  templateUrl: './player-page.component.html',
  styleUrl: './player-page.component.scss'
})
export class PlayerPageComponent {
  readonly username = signal('');
  readonly selectedIcon = signal('player-icons/saxon-player.svg');

  handleUsernameInput(value: string): void {
    this.username.set(value);
  }

  randomizeIcon(): void {
    // Placeholder until more icons exist
  }

  usernameIsValid(): boolean {
    return this.username().trim().length > 0;
  }

}
