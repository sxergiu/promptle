import { Component, Input, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { WebSocketService } from '../../../core/services/websocket.service';

@Component({
  selector: 'app-guessing',
  standalone: true,
  imports: [MatButtonModule],
  styleUrl: './guessing.component.scss',
  templateUrl: './guessing.component.html',
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
    if (this.submitted() || this.guessText().trim().length === 0) return;
    this.webSocketService.send(`/app/room/${this.roomCode}/guess`, { text: this.guessText() });
    this.submitted.set(true);
  }
}
