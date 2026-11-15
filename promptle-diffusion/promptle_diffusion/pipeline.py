"""Inference: a parity port of the ComfyUI proto-t2i / proto-i2i workflows.

Loads Stable Diffusion 1.5 once and shares its components between the
text-to-image and image-to-image pipelines (no double VRAM). Art style is
applied as a LoRA adapter (``load_lora_weights`` + per-call scale) instead of
the old "loud" prompt suffix — the LoRA scale is the new style-weight knob.

Parity values (config.py): euler/normal scheduler, 20 steps, CFG 8 (t2i) / 7
(i2i), strength 0.72 (i2i), 512x512, safety negative prompt always applied.
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image

from . import config as cfg
from .config import Config
from .safety import SAFETY_NEGATIVE_PROMPT, NsfwGate
from .styles import Style, by_name


class PromptleSD:
    def __init__(self, config: Config | None = None, nsfw_gate: NsfwGate | None = None):
        self.cfg = config or Config()
        self._t2i = None
        self._i2i = None
        self._loaded_adapters: set[str] = set()
        self._active_adapter: str | None = None
        self.gate = nsfw_gate  # None => no Layer-3 gate (e.g. when backend handles it)

    # ---- loading -----------------------------------------------------------
    def _build(self):
        if self._t2i is not None:
            return
        import torch
        from diffusers import (
            EulerDiscreteScheduler,
            StableDiffusionImg2ImgPipeline,
            StableDiffusionPipeline,
        )

        model = self.cfg.model_id
        kwargs = {"torch_dtype": self.cfg.torch_dtype, "safety_checker": None}
        if Path(model).exists():
            t2i = StableDiffusionPipeline.from_single_file(model, **kwargs)
        else:
            t2i = StableDiffusionPipeline.from_pretrained(model, **kwargs)

        # euler / normal  ==  EulerDiscreteScheduler with default (trailing off) config
        t2i.scheduler = EulerDiscreteScheduler.from_config(t2i.scheduler.config)
        t2i = t2i.to(self.cfg.device)

        # Share the loaded components -> no second copy in VRAM.
        i2i = StableDiffusionImg2ImgPipeline(**t2i.components)

        self._t2i, self._i2i = t2i, i2i

    # ---- style / LoRA ------------------------------------------------------
    def _resolve_style(self, style: str | Style | None) -> Style | None:
        if style is None or isinstance(style, Style):
            return style
        s = by_name(style)
        if s is None:
            raise KeyError(f"Unknown style {style!r}; not in styles.STYLES")
        return s

    def _apply_style(self, style: Style | None, scale: float) -> None:
        """Activate (loading if needed) the LoRA for a style, or disable LoRA."""
        self._build()
        if style is None:
            if self._active_adapter is not None:
                self._t2i.set_adapters([], [])
                self._active_adapter = None
            return

        lora_path = self.cfg.lora_dir / f"{style.slug}.safetensors"
        if style.slug not in self._loaded_adapters:
            if not lora_path.exists():
                raise FileNotFoundError(
                    f"No trained LoRA for style {style.name!r} at {lora_path}. "
                    "Train it first (train_lora.py)."
                )
            self._t2i.load_lora_weights(
                str(lora_path.parent), weight_name=lora_path.name, adapter_name=style.slug
            )
            self._loaded_adapters.add(style.slug)
        # i2i shares the same UNet/text-encoder, so this adapter applies to both.
        self._t2i.set_adapters([style.slug], [scale])
        self._active_adapter = style.slug

    # ---- generation --------------------------------------------------------
    def generate_t2i(
        self,
        prompt: str,
        style: str | Style | None = None,
        scale: float = cfg.DEFAULT_LORA_SCALE,
        seed: int | None = None,
    ) -> Image.Image:
        self._build()
        st = self._resolve_style(style)
        self._apply_style(st, scale)
        image = self._t2i(
            prompt=prompt,
            negative_prompt=SAFETY_NEGATIVE_PROMPT,
            num_inference_steps=cfg.T2I_STEPS,
            guidance_scale=cfg.T2I_CFG,
            width=cfg.RESOLUTION,
            height=cfg.RESOLUTION,
            generator=self._generator(seed),
        ).images[0]
        return self._guard(image)

    def generate_i2i(
        self,
        prompt: str,
        image: Image.Image,
        style: str | Style | None = None,
        scale: float = cfg.DEFAULT_LORA_SCALE,
        strength: float = cfg.I2I_STRENGTH,
        seed: int | None = None,
    ) -> Image.Image:
        self._build()
        st = self._resolve_style(style)
        self._apply_style(st, scale)
        init = image.convert("RGB").resize((cfg.RESOLUTION, cfg.RESOLUTION))
        out = self._i2i(
            prompt=prompt,
            image=init,
            strength=strength,                 # ComfyUI "denoise"
            negative_prompt=SAFETY_NEGATIVE_PROMPT,
            num_inference_steps=cfg.I2I_STEPS,
            guidance_scale=cfg.I2I_CFG,
            generator=self._generator(seed),
        ).images[0]
        return self._guard(out)

    # ---- helpers -----------------------------------------------------------
    def _generator(self, seed: int | None):
        s = seed if seed is not None else self.cfg.seed
        if s is None:
            return None
        import torch
        return torch.Generator(device=self.cfg.device).manual_seed(s)

    def _guard(self, image: Image.Image) -> Image.Image:
        return self.gate.filter(image) if self.gate else image
