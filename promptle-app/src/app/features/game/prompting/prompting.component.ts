import { Component, Input, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { WebSocketService } from '../../../core/services/websocket.service';

@Component({
  selector: 'app-prompting',
  standalone: true,
  imports: [MatButtonModule],
  styleUrl: './prompting.component.scss',
  templateUrl: './prompting.component.html',
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
    if (this.submitted() || this.promptText().trim().length === 0) return;
    this.webSocketService.send(`/app/room/${this.roomCode}/prompt`, { text: this.promptText() });
    this.submitted.set(true);
  }
}
