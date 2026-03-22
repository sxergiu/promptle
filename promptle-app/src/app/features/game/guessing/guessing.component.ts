import { Component, Input, OnInit, signal } from '@angular/core';

@Component({
  selector: 'app-guessing',
  standalone: true,
  imports: [],
  template: `
    <div>
      <img [src]="imageUrl" alt="guess image" />
      <input
        [readOnly]="submitted()"
        [value]="guessText()"
        (input)="guessText.set($any($event.target).value)"
      />
      <span>{{ submittedCount }} / {{ totalCount }} ready</span>
      <button
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

  ngOnInit(): void {
    if (this.hasSubmitted) {
      this.submitted.set(true);
    }
  }

  onSubmit(): void {
    this.submitted.set(true);
  }
}
