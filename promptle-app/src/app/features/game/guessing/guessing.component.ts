import { Component, Input, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { WebSocketService } from '../../../core/services/websocket.service';

@Component({
  selector: 'app-guessing',
  standalone: true,
  imports: [MatButtonModule],
  template: `
    <div>
      <img [src]="imageUrl" alt="Generated image" />
      <input
        [readOnly]="submitted()"
        [value]="guessText()"
        (input)="guessText.set($any($event.target).value)"
      />
      <span>{{ submittedCount }} / {{ totalCount }} ready</span>
      <button
        mat-raised-button
        [disabled]="submitted() || guessText().length === 0"
        (click)="onSubmit()"
      >Submit Guess</button>
    </div>
  `,
})
export class GuessingPhaseComponent implements OnInit {
  @Input() roomCode: string = '';
  @Input() imageUrl: string = '';
  @Input() submittedCount: number = 0;
  @Input() totalCount: number = 0;
  @Input() hasSubmitted: boolean = false;

  guessText = signal<string>('');
  submitted = signal<boolean>(false);

  constructor(private webSocketService: WebSocketService) {}

  ngOnInit(): void {
    if (this.hasSubmitted) {
      this.submitted.set(true);
    }
  }

  onSubmit(): void {
    if (this.submitted() || this.guessText().length === 0) return;
    this.webSocketService.send(`/app/room/${this.roomCode}/guess`, { text: this.guessText() });
    this.submitted.set(true);
  }
}
