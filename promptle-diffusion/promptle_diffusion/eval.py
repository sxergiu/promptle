"""Evaluate a trained style LoRA against the prompt-suffix baseline.

For a fixed set of content prompts, generates three variants per prompt:

  base    plain SD 1.5, no style                      (control)
  suffix  the CURRENT game approach: prompt + ", (<trigger> style:0.6)"
          (reproduces GameService.java's decorated prompt)  (baseline)
  lora    the trained style LoRA at the same scale     (ours)

and reports, per variant:

  clip_style  cosine similarity of generated images to the centroid of the
              style's reference images (datasets/<slug>/images) — higher = more
              on-style.
  fid         Frechet Inception Distance vs the reference set — lower = closer
              to the real style distribution.

The headline result is lora vs suffix: did training beat the prompt hack?

CLI (needs the `eval` extra: pip install -e '.[eval]'):
    python -m promptle_diffusion.eval --style watercolor
"""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image

from .config import DEFAULT_LORA_SCALE, Config
from .styles import STYLES, Style, by_slug

# Eval content deliberately differs from training content (data/build_dataset.py).
EVAL_PROMPTS = [
    "a fox in a meadow",
    "an old wooden bridge over a river",
    "a teacup on a saucer",
    "a castle on a hill at dawn",
    "a street musician playing guitar",
    "a sailboat on a calm lake",
    "a stack of books and a candle",
    "a deer in a snowy forest",
]


def _variants(sd, prompt: str, style: Style, scale: float, seed: int) -> dict[str, Image.Image]:
    suffix_prompt = f"{prompt}, ({style.trigger} style:{scale})"
    return {
        "base": sd.generate_t2i(prompt, style=None, seed=seed),
        "suffix": sd.generate_t2i(suffix_prompt, style=None, seed=seed),
        "lora": sd.generate_t2i(prompt, style=style, scale=scale, seed=seed),
    }


def _clip_centroid_and_score(ref_images: list[Path], gen_images: list[Image.Image], device: str):
    import open_clip
    import torch

    model, _, preprocess = open_clip.create_model_and_transforms("ViT-B-32", pretrained="openai")
    model = model.to(device).eval()

    @torch.no_grad()
    def embed(imgs):
        batch = torch.stack([preprocess(im.convert("RGB")) for im in imgs]).to(device)
        feats = model.encode_image(batch)
        return feats / feats.norm(dim=-1, keepdim=True)

    ref = embed([Image.open(p) for p in ref_images])
    centroid = ref.mean(dim=0, keepdim=True)
    centroid = centroid / centroid.norm()
    gen = embed(gen_images)
    return float((gen @ centroid.T).mean().item())


def _fid(gen_dir: Path, ref_dir: Path, device: str) -> float:
    from pytorch_fid.fid_score import calculate_fid_given_paths

    return float(
        calculate_fid_given_paths([str(ref_dir), str(gen_dir)], batch_size=16, device=device, dims=2048)
    )


def evaluate_style(
    style: Style, config: Config, scale: float = DEFAULT_LORA_SCALE, seed: int = 7
) -> dict:
    from .pipeline import PromptleSD

    ref_dir = config.dataset_dir / style.slug / "images"
    ref_images = sorted(ref_dir.glob("*.png"))
    if not ref_images:
        raise FileNotFoundError(f"No reference images at {ref_dir}; build the dataset first.")

    sd = PromptleSD(config)
    out_root = config.output_dir / "eval" / style.slug
    by_variant: dict[str, list[Image.Image]] = {"base": [], "suffix": [], "lora": []}
    for i, prompt in enumerate(EVAL_PROMPTS):
        for name, img in _variants(sd, prompt, style, scale, seed + i).items():
            d = out_root / name
            d.mkdir(parents=True, exist_ok=True)
            img.save(d / f"{i:02d}.png")
            by_variant[name].append(img)

    results = {}
    for name, imgs in by_variant.items():
        results[name] = {
            "clip_style": _clip_centroid_and_score(ref_images, imgs, config.device),
            "fid": _fid(out_root / name, ref_dir, config.device),
        }

    print(f"\n=== {style.name} (scale={scale}) ===")
    print(f"{'variant':8}  {'clip_style↑':>12}  {'fid↓':>8}")
    for name in ("base", "suffix", "lora"):
        r = results[name]
        print(f"{name:8}  {r['clip_style']:>12.4f}  {r['fid']:>8.2f}")
    win = results["lora"]["clip_style"] > results["suffix"]["clip_style"]
    print(f"LoRA beats suffix on CLIP-style: {win}")
    return results


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--style", help="style slug")
    g.add_argument("--all", action="store_true")
    ap.add_argument("--scale", type=float, default=DEFAULT_LORA_SCALE)
    args = ap.parse_args()

    config = Config()
    styles = list(STYLES) if args.all else [by_slug(args.style)]
    if styles == [None]:
        raise SystemExit(f"Unknown style slug {args.style!r}")
    for st in styles:
        evaluate_style(st, config, scale=args.scale)


if __name__ == "__main__":
    main()
