import { Component } from '@angular/core';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

@Component({
  selector: 'app-generating',
  standalone: true,
  imports: [MatProgressSpinnerModule],
  template: `
    <div>
      <mat-spinner></mat-spinner>
      <p>Generating...</p>
    </div>
  `,
})
export class GeneratingComponent {}
