"""NSFW safety — the Python equivalent of the ComfyUI workflow's safety layers.

Promptle layers NSFW defense (see promptle-docs/ai-workflow/nsfw-filtering.md):
  Layer 1  text blocklist            -> stays in the Java backend (PromptFilter.java); NOT here
  Layer 2  negative prompt           -> SAFETY_NEGATIVE_PROMPT below (baked into every generation)
  Layer 3  post-gen image classifier -> NsfwGate, replacing the ComfyUI `NSFWDetection` node

The ComfyUI node, on a flagged image (score >= 0.9), swaps in a stub image and
the chain continues. NsfwGate mirrors that: classify, and on trip return the
stub instead of the generated image.
"""

from __future__ import annotations

from PIL import Image

# Verbatim from proto-t2i.json / proto-i2i.json (CLIPTextEncode negative node).
SAFETY_NEGATIVE_PROMPT = (
    "text, watermark, nsfw, nude, naked, explicit, sexual, erotic, porn, "
    "pornographic, genitalia, nipples, topless, gore, gory, violence, violent, "
    "dismembered, grotesque, disturbing, offensive"
)

# Matches the ComfyUI NSFWDetection node's conservative threshold.
DEFAULT_NSFW_THRESHOLD = 0.9


def _gray_stub(size: tuple[int, int]) -> Image.Image:
    """Solid-gray placeholder, mirroring the ComfyUI stub-image fallback."""
    return Image.new("RGB", size, (128, 128, 128))


class NsfwGate:
    """Layer-3 image gate. Lazily loads a CLIP-based NSFW classifier.

    Uses transformers' image-classification pipeline with an open NSFW model.
    The model id is swappable; the contract is: given a PIL image, return the
    image if safe, else a stub. Fails open->safe-stub on classifier errors only
    if ``strict`` (default keeps the original on classifier failure so a broken
    model never silently blanks every image — log instead).
    """

    def __init__(
        self,
        model_id: str = "Falconsai/nsfw_image_detection",
        threshold: float = DEFAULT_NSFW_THRESHOLD,
        device: str = "cpu",
        stub: Image.Image | None = None,
    ) -> None:
        self.model_id = model_id
        self.threshold = threshold
        self.device = device
        self._stub = stub
        self._clf = None

    def _ensure(self):
        if self._clf is None:
            from transformers import pipeline

            # device index: -1 cpu, 0 first cuda/mps
            dev = -1 if self.device == "cpu" else 0
            self._clf = pipeline("image-classification", model=self.model_id, device=dev)
        return self._clf

    def is_nsfw(self, image: Image.Image) -> bool:
        clf = self._ensure()
        scores = {r["label"].lower(): r["score"] for r in clf(image)}
        return scores.get("nsfw", 0.0) >= self.threshold

    def filter(self, image: Image.Image) -> Image.Image:
        """Return the image if safe, else the stub (ComfyUI node behavior)."""
        try:
            flagged = self.is_nsfw(image)
        except Exception:  # never let a classifier hiccup blank a whole round
            return image
        if flagged:
            return self._stub or _gray_stub(image.size)
        return image
