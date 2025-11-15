import { Component, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { ComfyUiService } from '../service/comfyui.service';
import {RouterLink} from '@angular/router';

@Component({
  selector: 'app-image-generator',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './image-generator.component.html',
  styleUrls: ['./image-generator.component.scss']
})
export class ImageGeneratorComponent {
  // signals state
  prompt    = signal<string>('');
  loading   = signal<boolean>(false);
  error     = signal<string>('');
  imageUrl  = signal<string | null>(null);

  constructor(private comfy: ComfyUiService) {}

  async generate() {
    this.error.set('');
    this.imageUrl.set(null);

    const text = this.prompt().trim();
    if (!text) {
      this.error.set('Please enter a prompt.');
      console.log('no prompt')
      return;
    }

    this.loading.set(true);
    try {
      const id  = await this.comfy.submitPrompt(text);
      const url = await this.comfy.waitForImage(id);
      this.imageUrl.set(url);
    } catch (e: any) {
      this.error.set(e?.message ?? 'Something went wrong.');
    } finally {
      this.loading.set(false);
    }
  }
}
