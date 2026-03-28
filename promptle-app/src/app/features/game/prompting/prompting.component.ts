import { Component, Input, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { WebSocketService } from '../../../core/services/websocket.service';

@Component({
  selector: 'app-prompting',
  standalone: true,
  imports: [MatButtonModule],
  template: `
    <div>
      <textarea
        [readOnly]="submitted()"
        [value]="promptText()"
        (input)="promptText.set($any($event.target).value)"
      ></textarea>
      <span>{{ submittedCount }} / {{ totalCount }} ready</span>
      <button
        mat-raised-button
        [disabled]="submitted() || promptText().length === 0"
        (click)="onSubmit()"
      >Ready</button>
    </div>
  `,
})
export class PromptingPhaseComponent implements OnInit {
  @Input() roomCode: string = '';
  @Input() submittedCount: number = 0;
  @Input() totalCount: number = 0;
  @Input() hasSubmitted: boolean = false;

  promptText = signal<string>('');
  submitted = signal<boolean>(false);

  constructor(private webSocketService: WebSocketService) {}

  ngOnInit(): void {
    if (this.hasSubmitted) {
      this.submitted.set(true);
    }
  }

  onSubmit(): void {
    if (this.submitted() || this.promptText().length === 0) return;
    this.webSocketService.send(`/app/room/${this.roomCode}/prompt`, { text: this.promptText() });
    this.submitted.set(true);
  }
}
