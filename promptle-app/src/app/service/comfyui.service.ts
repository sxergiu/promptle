import { Injectable, inject } from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class ComfyUiService {
  private http = inject(HttpClient);
  private readonly base = 'http://127.0.0.1:8188';
  ts = Date.now();

  private buildPromptGraph(textPrompt: string) {
    const prompt = {
      "3": {
        "class_type": "CheckpointLoaderSimple",
        "inputs": { "ckpt_name": "v1-5-pruned-emaonly-fp16.safetensors" }
      },
      "4": { "class_type": "CLIPTextEncode", "inputs": { "text": textPrompt, "clip": ["3", 1] } },
      "5": { "class_type": "CLIPTextEncode", "inputs": { "text": "low quality, bad anatomy", "clip": ["3", 1] } },
      "6": { "class_type": "EmptyLatentImage", "inputs": { "width": 768, "height": 768, "batch_size": 1 } },
      "7": {
        "class_type": "KSampler",
        "inputs": {
          "seed": Math.floor(Math.random() * 1e9),
          "steps": 25,
          "cfg": 7,
          "sampler_name": "euler",
          "scheduler": "normal",
          "denoise": 1,
          "model": ["3", 0],
          "positive": ["4", 0],
          "negative": ["5", 0],
          "latent_image": ["6", 0]
        }
      },
      "8": { "class_type": "VAEDecode", "inputs": { "samples": ["7", 0], "vae": ["3", 2] } },
      "9": {
        "class_type": "SaveImage",
        "inputs": {
          "images": ["8", 0],
          // ✅ these satisfy stricter SaveImage validators
          "filename_prefix": `angular_${this.ts}`,
          "subfolder": "",          // some builds expect this
          "overwrite": false,       // and this
          "format": "png",          // some builds use "format", others "extension"
          "quality": 95,            // optional, harmless if ignored
          "disable_metadata": false // optional
        }
      }
    };

    return { prompt, client_id: crypto.randomUUID() };
  }

  async submitPrompt(textPrompt: string): Promise<string> {
    const body = this.buildPromptGraph(textPrompt);
    try {
      const res = await firstValueFrom(
        this.http.post<{ prompt_id: string }>(`${this.base}/prompt`, body)
      );
      if (!res?.prompt_id) throw new Error('No prompt_id returned by ComfyUI');
      return res.prompt_id;
    } catch (err) {
      const e = err as HttpErrorResponse;
      console.error('ComfyUI /prompt error:', {
        status: e.status,
        statusText: e.statusText,
        serverMessage: e.error, // <-- this is what we need
      });
      throw e;
    }
  }

  async waitForImage(promptId: string, timeoutMs = 120000000, pollMs = 1000): Promise<string> {
    const started = Date.now();
    while (Date.now() - started < timeoutMs) {
      const history = await firstValueFrom(this.http.get<any>(`${this.base}/history/${promptId}`));
      const record = history?.[promptId];
      const outputs = record?.outputs;
      if (outputs) {
        for (const nodeId of Object.keys(outputs)) {
          const node = outputs[nodeId];
          if (node?.images?.length) {
            const first = node.images[0];
            const url = `${this.base}/view?filename=${encodeURIComponent(first.filename)}&subfolder=${encodeURIComponent(first.subfolder || '')}&type=${encodeURIComponent(first.type || 'output')}`;
            return url;
          }
        }
      }
      await new Promise(r => setTimeout(r, pollMs));
    }
    throw new Error('Timed out waiting for image.');
  }
}
