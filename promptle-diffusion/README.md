# promptle-diffusion

Style-LoRA **training** + **inference** for Promptle, on Stable Diffusion 1.5.

Replaces the "loud" prompt-suffix style hack (`", (watercolor texture style:0.6)"`,
`GameService.java`) with one trained LoRA adapter per art style. Content stays prompt-driven;
**style** lives in adapter weights, with the LoRA scale as the (better-behaved) style knob.
Inference is a parity port of the ComfyUI `proto-t2i` / `proto-i2i` workflows.

## Layout

```
promptle_diffusion/
  config.py          hardware-portable config (local GPU → cloud is config-only)
  styles.py          the 12 styles + LoRA mapping — SYNC w/ backend ArtStyleSeeder.DEFAULTS
  pipeline.py        inference: t2i + i2i, LoRA load/scale, parity params
  safety.py          NSFW Layer-2 (negative prompt) + Layer-3 (image classifier + stub)
  train_lora.py      per-style LoRA training (diffusers + peft)
  eval.py            CLIP-style score + FID + A/B vs the prompt-suffix baseline
  data/
    build_dataset.py curate (Met CC0) + synthetic bootstrap per style
    caption.py       BLIP captions + trigger-phrase injection
  serve.py           (Phase 4) FastAPI for the backend `python` provider — TODO
notebooks/
  promptle_style_lora.ipynb   end-to-end train + evaluate
```

## Setup

```bash
cd promptle-diffusion
python -m venv .venv && source .venv/bin/activate
pip install -e '.[eval,notebook]'          # add ',perf' on Linux/CUDA for bitsandbytes+xformers
export PROMPTLE_SD_MODEL=stable-diffusion-v1-5/stable-diffusion-v1-5   # or a local diffusers folder
```

Device/dtype auto-detect (CUDA → MPS → CPU). Large artifacts (`models/`, `datasets/`,
`lora/`, `outputs/`) are gitignored.

## Workflow

```bash
# 1. dataset for one style  (curated => Met API; synthetic => SD bootstrap)
python -m promptle_diffusion.data.build_dataset fetch   --style watercolor --limit 150
#    ...manually cull bad images in datasets/watercolor/raw/...
python -m promptle_diffusion.data.build_dataset prepare --style watercolor
python -m promptle_diffusion.data.caption               --style watercolor

# 2. train its LoRA  ->  lora/watercolor.safetensors
python -m promptle_diffusion.train_lora --style watercolor --steps 1200

# 3. evaluate vs the prompt-suffix baseline
python -m promptle_diffusion.eval --style watercolor
```

Or run `notebooks/promptle_style_lora.ipynb` for the whole loop with a comparison grid.

## Inference (in code)

```python
from promptle_diffusion import PromptleSD
sd = PromptleSD()
img = sd.generate_t2i("a fox in a meadow", style="watercolor texture", scale=0.6)
```

`style` is the backend `ArtStyle.name` (the game's lookup key). i2i: `sd.generate_i2i(prompt, image, style=...)`.

## Parity with ComfyUI (do not drift)

| | t2i | i2i |
|---|---|---|
| base | SD 1.5 `v1-5-pruned-emaonly-fp16` | same |
| scheduler / steps | euler / 20 | euler / 20 |
| CFG | 8 | 7 |
| denoise·strength | 1.0 | 0.72 |
| res | 512² | from input |

Safety: Layer-1 text blocklist stays in the **Java backend** (`PromptFilter.java`) — not ported
here. This package handles Layer-2 (negative prompt) + Layer-3 (image classifier → stub).

## Notes

- **`styles.py` is the source of truth** for style↔LoRA. Its `name` fields must stay identical
  to `ArtStyleSeeder.DEFAULTS` in the backend.
- **Trademark:** the `"soft studio ghibli anime shading"` backend key is trained as the
  trademark-free `"soft anime cel shading"` (`Style.trigger`).
- **Phase 4 (backend serving)** — `serve.py` + implementing `PythonWorkflowGenerationService` +
  the `GameService` style-field contract change — is intentionally deferred; see the plan.
```
