import { Component, OnDestroy, signal, computed } from '@angular/core';

@Component({
  selector: 'app-results',
  standalone: true,
  imports: [],
  template: `
    <div>
      @for (chain of chains(); track $index) {
        @if ($index === currentChainIndex()) {
          @for (entry of chain.entries; track $index; let i = $index) {
            @if (i < revealedEntryCount()) {
              <div>
                <span>{{ entry.text }}</span>
                @if (entry.imageUrl) {
                  <img [src]="entry.imageUrl" alt="entry image" />
                }
              </div>
            }
          }
        }
      }
      <button
        [disabled]="!isHost() || !allRevealed()"
        (click)="onNextChain()"
      >
        {{ currentChainIndex() < chains().length - 1 ? 'Next' : 'Back to Lobby' }}
      </button>
      <button (click)="onExportThread()">Export Thread</button>
    </div>
  `,
})
export class ResultsComponent implements OnDestroy {
  chains = signal<any[]>([]);
  currentChainIndex = signal<number>(0);
  revealedEntryCount = signal<number>(0);
  isHost = signal<boolean>(false);

  readonly revealIntervalMs: number = 3000;

  private _intervalId: any = null;

  readonly allRevealed = computed(() => {
    const chain = this.chains()[this.currentChainIndex()];
    if (!chain) return false;
    return this.revealedEntryCount() >= chain.entries.length;
  });

  startRevealInterval(): void {
    this._clearInterval();
    this._intervalId = setInterval(() => {
      const chain = this.chains()[this.currentChainIndex()];
      if (!chain) return;
      const max = chain.entries.length;
      this.revealedEntryCount.update(v => v < max ? v + 1 : v);
    }, this.revealIntervalMs);
  }

  onNextChain(): void {}

  onBackToLobby(): void {}

  onExportThread(): void {}

  ngOnDestroy(): void {
    this._clearInterval();
  }

  private _clearInterval(): void {
    if (this._intervalId !== null) {
      clearInterval(this._intervalId);
      this._intervalId = null;
    }
  }
}
