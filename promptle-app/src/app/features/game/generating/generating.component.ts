import { Component } from '@angular/core';

@Component({
  selector: 'app-generating',
  standalone: true,
  imports: [],
  template: `
    <div>
      <div role="progressbar" class="spinner"></div>
      <p>Generating...</p>
    </div>
  `,
})
export class GeneratingComponent {}
