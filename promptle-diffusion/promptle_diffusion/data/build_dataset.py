"""Build per-style training image sets (hybrid sourcing).

Two sources, chosen per style by ``Style.source`` (see styles.py):

  curated   -> public-domain art via the Metropolitan Museum Open Access API
               (CC0). Best for art-historical looks (oil, woodblock, etc.).
  synthetic -> bootstrap with the current SD 1.5 + a strong style prompt over a
               list of varied content nouns, then MANUALLY CULL. For modern
               looks the museum API can't supply (pixel-art, vaporwave, ...).

Layout produced:
    datasets/<slug>/raw/        # sourced images (pre-cull, pre-resize)
    datasets/<slug>/images/     # culled + 512x512, ready for captioning

Cull step is manual by design: after `fetch`, delete bad images from raw/,
then run `prepare` to resize the survivors into images/.

CLI:
    python -m promptle_diffusion.data.build_dataset fetch   --style watercolor [--limit 150]
    python -m promptle_diffusion.data.build_dataset fetch   --all
    python -m promptle_diffusion.data.build_dataset prepare --style watercolor
"""

from __future__ import annotations

import argparse
import io
import time
from pathlib import Path

import requests
from PIL import Image

from ..config import RESOLUTION, Config
from ..styles import STYLES, Style, by_slug

MET_SEARCH = "https://collectionapi.metmuseum.org/public/collection/v1/search"
MET_OBJECT = "https://collectionapi.metmuseum.org/public/collection/v1/objects/{}"

# Search terms per curated style (Met Open Access). Tuned toward the *look*.
MET_QUERIES: dict[str, str] = {
    "watercolor": "watercolor painting",
    "oil-paint": "oil painting",
    "comic": "ink drawing illustration",
    "woodblock": "japanese woodblock print",
    "art-nouveau": "art nouveau",
    "charcoal": "charcoal drawing",
    "retro-poster": "vintage travel poster",
    "stained-glass": "stained glass",
}

# Varied content for synthetic bootstrap (style is held constant, content varies
# so the LoRA learns style independent of subject).
SYNTHETIC_CONTENT = [
    "a mountain landscape", "a city street at night", "a portrait of a woman",
    "a cat sitting on a windowsill", "a bowl of fruit", "a forest path",
    "a spaceship over a planet", "a cozy cafe interior", "a dragon on a cliff",
    "a vintage car", "a lighthouse by the sea", "a robot in a garden",
    "a bustling market", "a quiet library", "a field of flowers",
    "a snowy village", "a desert at sunset", "a koi pond",
    "a knight in armor", "a hot air balloon",
]


def _met_object_ids(query: str, limit: int) -> list[int]:
    r = requests.get(MET_SEARCH, params={"q": query, "hasImages": "true"}, timeout=30)
    r.raise_for_status()
    ids = r.json().get("objectIDs") or []
    # Oversample: many objects are not public-domain / lack a primaryImage.
    return ids[: limit * 4]


def fetch_curated(style: Style, out: Path, limit: int) -> int:
    query = MET_QUERIES.get(style.slug, style.trigger)
    saved = 0
    for oid in _met_object_ids(query, limit):
        if saved >= limit:
            break
        try:
            obj = requests.get(MET_OBJECT.format(oid), timeout=30).json()
            if not obj.get("isPublicDomain") or not obj.get("primaryImage"):
                continue
            img_bytes = requests.get(obj["primaryImage"], timeout=60).content
            Image.open(io.BytesIO(img_bytes)).convert("RGB").save(out / f"met_{oid}.jpg")
            saved += 1
            time.sleep(0.2)  # be polite to the API
        except Exception:
            continue
    return saved


def fetch_synthetic(style: Style, out: Path, limit: int, config: Config) -> int:
    # Imported lazily so curated-only runs don't need torch/diffusers loaded.
    from ..pipeline import PromptleSD

    sd = PromptleSD(config)
    saved = 0
    i = 0
    while saved < limit:
        content = SYNTHETIC_CONTENT[i % len(SYNTHETIC_CONTENT)]
        prompt = f"{content}, {style.trigger}"
        # No LoRA yet (style=None) — the strong style phrase carries the look.
        img = sd.generate_t2i(prompt, style=None, seed=1000 + i)
        img.save(out / f"syn_{i:04d}.png")
        saved += 1
        i += 1
    return saved


def fetch(style: Style, config: Config, limit: int) -> int:
    raw = config.dataset_dir / style.slug / "raw"
    raw.mkdir(parents=True, exist_ok=True)
    if style.source == "curated":
        n = fetch_curated(style, raw, limit)
    else:
        n = fetch_synthetic(style, raw, limit, config)
    print(f"[{style.slug}] fetched {n} images -> {raw} (source={style.source})")
    print(f"  Now MANUALLY CULL bad images in {raw}, then run `prepare --style {style.slug}`.")
    return n


def prepare(style: Style, config: Config) -> int:
    raw = config.dataset_dir / style.slug / "raw"
    images = config.dataset_dir / style.slug / "images"
    images.mkdir(parents=True, exist_ok=True)
    n = 0
    for p in sorted(raw.glob("*")):
        if p.suffix.lower() not in {".jpg", ".jpeg", ".png", ".webp"}:
            continue
        try:
            img = Image.open(p).convert("RGB")
        except Exception:
            continue
        # center-crop to square, then resize to training resolution
        w, h = img.size
        s = min(w, h)
        img = img.crop(((w - s) // 2, (h - s) // 2, (w + s) // 2, (h + s) // 2))
        img = img.resize((RESOLUTION, RESOLUTION), Image.LANCZOS)
        img.save(images / f"{p.stem}.png")
        n += 1
    print(f"[{style.slug}] prepared {n} images -> {images}")
    return n


def _styles_arg(args) -> list[Style]:
    if args.all:
        return list(STYLES)
    s = by_slug(args.style)
    if s is None:
        raise SystemExit(f"Unknown style slug {args.style!r}")
    return [s]


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for cmd in ("fetch", "prepare"):
        p = sub.add_parser(cmd)
        g = p.add_mutually_exclusive_group(required=True)
        g.add_argument("--style", help="style slug (see styles.py)")
        g.add_argument("--all", action="store_true")
        if cmd == "fetch":
            p.add_argument("--limit", type=int, default=150)
    args = ap.parse_args()

    config = Config()
    config.ensure_dirs()
    for st in _styles_arg(args):
        if args.cmd == "fetch":
            fetch(st, config, args.limit)
        else:
            prepare(st, config)


if __name__ == "__main__":
    main()
