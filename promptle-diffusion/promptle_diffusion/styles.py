"""The closed set of Promptle art styles and their LoRA metadata.

SINGLE SOURCE OF TRUTH for the style <-> LoRA mapping. The ``name`` field MUST
stay byte-identical to the entries in the backend's
``promptle/src/main/java/com/app/promptle/config/ArtStyleSeeder.java``
(``DEFAULTS``), because the game passes ``Chain.style`` (== ``ArtStyle.name``)
as the lookup key when requesting an image. If you add/rename a style there,
mirror it here.

Why ``name`` and ``trigger`` can differ: ``name`` is the backend lookup key;
``trigger`` is the phrase the LoRA is actually trained on and re-used at
inference. For "soft studio ghibli anime shading" we keep the backend key but
train on the trademark-free "soft anime cel shading" (see README).
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Style:
    name: str           # backend ArtStyle.name — the lookup key from the game
    slug: str           # filesystem-safe id (lora filename, dataset dir)
    trigger: str        # phrase the LoRA learns / is invoked with
    source: str         # "curated" (CC0/WikiArt) or "synthetic" (bootstrap+cull)


# Order/text mirrors ArtStyleSeeder.DEFAULTS (2026-06).
STYLES: list[Style] = [
    Style("watercolor texture",            "watercolor",   "watercolor texture",            "curated"),
    Style("pixel-art shading",             "pixel-art",    "pixel-art shading",             "synthetic"),
    Style("oil-paint texture",             "oil-paint",    "oil-paint texture",             "curated"),
    Style("comic-book inking",             "comic",        "comic-book inking",             "curated"),
    Style("japanese woodblock linework",   "woodblock",    "japanese woodblock linework",   "curated"),
    Style("art nouveau ornamentation",     "art-nouveau",  "art nouveau ornamentation",     "curated"),
    Style("subtle cyberpunk neon accents", "cyberpunk",    "subtle cyberpunk neon accents", "synthetic"),
    Style("charcoal shading",              "charcoal",     "charcoal shading",              "curated"),
    # Backend key keeps "studio ghibli"; trained target is trademark-free.
    Style("soft studio ghibli anime shading", "anime-cel", "soft anime cel shading",        "synthetic"),
    Style("retro poster coloring",         "retro-poster", "retro poster coloring",         "curated"),
    Style("stained-glass color segments",  "stained-glass","stained-glass color segments",  "curated"),
    Style("subtle vaporwave color grading","vaporwave",    "subtle vaporwave color grading","synthetic"),
]

_BY_NAME = {s.name: s for s in STYLES}
_BY_SLUG = {s.slug: s for s in STYLES}


def by_name(name: str) -> Style | None:
    """Look up a style by the backend's ArtStyle.name (the game's lookup key)."""
    return _BY_NAME.get(name)


def by_slug(slug: str) -> Style | None:
    return _BY_SLUG.get(slug)
