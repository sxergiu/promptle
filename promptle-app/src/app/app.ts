import { Component, inject, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ThemeService } from './core/services/theme.service';
import { SoundService } from './core/services/sound.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet],
  templateUrl: './app.html',
  styleUrls: ['./app.scss']
})
export class App {
  protected theme = inject(ThemeService);
  protected sound = inject(SoundService);

  /** Briefly shown "Sound ON/OFF" confirmation after toggling. */
  protected soundLabelVisible = signal(false);
  private _soundLabelTimer?: ReturnType<typeof setTimeout>;

  protected onSoundToggle(): void {
    this.sound.toggle();
    this.soundLabelVisible.set(true);
    clearTimeout(this._soundLabelTimer);
    this._soundLabelTimer = setTimeout(() => this.soundLabelVisible.set(false), 1400);
  }
}
