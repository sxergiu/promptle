import { Component, OnDestroy, signal, computed } from '@angular/core';
import { GamePhase } from '../../core/models/game-phase.enum';
import { PromptingPhaseComponent } from './prompting/prompting.component';
import { GeneratingComponent } from './generating/generating.component';
import { GuessingPhaseComponent } from './guessing/guessing.component';

@Component({
  selector: 'app-game',
  standalone: true,
  imports: [PromptingPhaseComponent, GeneratingComponent, GuessingPhaseComponent],
  template: `
    @if (phase() === GamePhase.PROMPTING) {
      <app-prompting></app-prompting>
    }
    @if (phase() === GamePhase.GENERATING) {
      <app-generating></app-generating>
    }
    @if (phase() === GamePhase.GUESSING) {
      <app-guessing></app-guessing>
    }
    @if (!wsConnected()) {
      <div class="reconnecting">Reconnecting...</div>
    }
  `,
})
export class GameComponent implements OnDestroy {
  readonly GamePhase = GamePhase;

  phase = signal<GamePhase>(GamePhase.PROMPTING);
  currentRound = signal<number>(1);
  totalRounds = signal<number>(1);
  timerSeconds = signal<number>(0);
  serverTimestamp = signal<number>(0);
  imageUrl = signal<string | null>(null);
  submittedCount = signal<number>(0);
  wsConnected = signal<boolean>(true);

  readonly remainingSeconds = computed(() => {
    const elapsed = (Date.now() - this.serverTimestamp()) / 1000;
    return Math.max(0, Math.floor(this.timerSeconds() - elapsed));
  });

  ngOnDestroy(): void {}
}
