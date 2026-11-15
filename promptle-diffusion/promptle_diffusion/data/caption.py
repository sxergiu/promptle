"""Caption a style's prepared images with BLIP and append the style trigger.

Produces a diffusers-ready ``metadata.jsonl`` (imagefolder format) next to the
images, one record per line: ``{"file_name": "...", "text": "<caption>, <trigger>"}``.

The BLIP caption describes *content* (varied), and the appended trigger phrase
binds the *style* to that phrase — so the LoRA learns style independent of
subject and is invoked later via the same trigger.

CLI:
    python -m promptle_diffusion.data.caption --style watercolor
    python -m promptle_diffusion.data.caption --all
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from PIL import Image

from ..config import Config
from ..styles import STYLES, Style, by_slug


class Blip:
    """Lazy BLIP captioner (Salesforce/blip-image-captioning-base)."""

    def __init__(self, model_id: str = "Salesforce/blip-image-captioning-base", device: str = "cpu"):
        self.model_id = model_id
        self.device = device
        self._proc = None
        self._model = None

    def _ensure(self):
        if self._model is None:
            import torch  # noqa: F401
            from transformers import BlipForConditionalGeneration, BlipProcessor

            self._proc = BlipProcessor.from_pretrained(self.model_id)
            self._model = BlipForConditionalGeneration.from_pretrained(self.model_id).to(self.device)

    def caption(self, image: Image.Image) -> str:
        self._ensure()
        inputs = self._proc(image, return_tensors="pt").to(self.device)
        out = self._model.generate(**inputs, max_new_tokens=40)
        return self._proc.decode(out[0], skip_special_tokens=True).strip()


def caption_style(style: Style, config: Config, blip: Blip) -> int:
    images = config.dataset_dir / style.slug / "images"
    if not images.is_dir():
        print(f"[{style.slug}] no images/ dir — run build_dataset prepare first; skipping")
        return 0
    records = []
    for p in sorted(images.glob("*.png")):
        try:
            cap = blip.caption(Image.open(p).convert("RGB"))
        except Exception as e:
            print(f"  caption failed for {p.name}: {e}")
            continue
        text = f"{cap}, {style.trigger}"
        records.append({"file_name": p.name, "text": text})
    meta = images / "metadata.jsonl"
    with meta.open("w") as f:
        for r in records:
            f.write(json.dumps(r) + "\n")
    print(f"[{style.slug}] captioned {len(records)} images -> {meta}")
    return len(records)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--style", help="style slug")
    g.add_argument("--all", action="store_true")
    args = ap.parse_args()

    config = Config()
    styles = list(STYLES) if args.all else [by_slug(args.style)]
    if styles == [None]:
        raise SystemExit(f"Unknown style slug {args.style!r}")
    blip = Blip(device=config.device)
    for st in styles:
        caption_style(st, config, blip)


if __name__ == "__main__":
    main()
