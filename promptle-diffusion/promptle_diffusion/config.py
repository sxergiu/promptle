"""Hardware-portable runtime configuration.

Everything that differs between a local GPU box and a cloud instance lives here,
so moving environments is a config change (env vars / a different Config()) and
never a code change. Defaults auto-detect the best available device.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _default_device() -> str:
    try:
        import torch
    except ImportError:
        return "cpu"
    if torch.cuda.is_available():
        return "cuda"
    if getattr(torch.backends, "mps", None) and torch.backends.mps.is_available():
        return "mps"
    return "cpu"


def _default_dtype(device: str) -> str:
    # fp16 on CUDA/MPS, fp32 on CPU (fp16 on CPU is slow/unsupported for many ops).
    return "float32" if device == "cpu" else "float16"


# Parity constants pulled from the ComfyUI workflows (proto-t2i.json / proto-i2i.json)
# and promptle-docs/fine-tune. Keep these in sync with application.properties.
T2I_STEPS = 20
T2I_CFG = 8.0
I2I_STEPS = 20
I2I_CFG = 7.0
I2I_STRENGTH = 0.72        # ComfyUI "denoise" for img2img
RESOLUTION = 512
DEFAULT_LORA_SCALE = 0.6   # mirrors image.style.weight.* (the old prompt-suffix weight)


@dataclass
class Config:
    # Base model: a local SD 1.5 .safetensors path OR a HF repo id.
    model_id: str = field(
        default_factory=lambda: os.environ.get(
            # The original runwayml/stable-diffusion-v1-5 HF repo was taken down;
            # this community re-host is the live diffusers-folder equivalent
            # (required for training — a single .safetensors is inference-only).
            "PROMPTLE_SD_MODEL", "stable-diffusion-v1-5/stable-diffusion-v1-5"
        )
    )
    device: str = field(default_factory=_default_device)
    dtype: str = ""                       # resolved in __post_init__ if empty
    seed: int | None = None               # None => random per call; set for reproducible eval

    # Filesystem roots (all gitignored). Relative to the package root by default.
    root: Path = field(
        default_factory=lambda: Path(
            os.environ.get("PROMPTLE_DIFFUSION_ROOT", Path(__file__).resolve().parent.parent)
        )
    )
    lora_dir: Path = field(default=None)      # type: ignore[assignment]
    dataset_dir: Path = field(default=None)   # type: ignore[assignment]
    output_dir: Path = field(default=None)    # type: ignore[assignment]

    def __post_init__(self) -> None:
        if not self.dtype:
            self.dtype = _default_dtype(self.device)
        self.root = Path(self.root)
        if self.lora_dir is None:
            self.lora_dir = self.root / "lora"
        if self.dataset_dir is None:
            self.dataset_dir = self.root / "datasets"
        if self.output_dir is None:
            self.output_dir = self.root / "outputs"

    @property
    def torch_dtype(self):
        import torch
        return {"float16": torch.float16, "bfloat16": torch.bfloat16, "float32": torch.float32}[
            self.dtype
        ]

    def ensure_dirs(self) -> None:
        for d in (self.lora_dir, self.dataset_dir, self.output_dir):
            d.mkdir(parents=True, exist_ok=True)
