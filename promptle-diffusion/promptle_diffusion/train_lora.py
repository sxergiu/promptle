"""Per-style LoRA fine-tuning of SD 1.5 (diffusers + peft).

A compact, self-contained version of the official diffusers
``train_text_to_image_lora`` recipe: LoRA on the UNet cross-attention only
(text encoder frozen). Reads a style's captioned imagefolder
(``datasets/<slug>/images`` + ``metadata.jsonl``) and writes one diffusers-
compatible adapter to ``lora/<slug>.safetensors`` — loadable by
``PromptleSD`` via ``load_lora_weights(weight_name=...)``.

Training needs the base model as an HF repo id or a local *diffusers folder*
(not a single .safetensors — that's inference-only). Set PROMPTLE_SD_MODEL or
pass --model.

CLI:
    python -m promptle_diffusion.train_lora --style watercolor
    python -m promptle_diffusion.train_lora --all --steps 1200
"""

from __future__ import annotations

import argparse
import math
from dataclasses import dataclass
from pathlib import Path

from .config import RESOLUTION, Config
from .styles import STYLES, Style, by_slug


@dataclass
class TrainConfig:
    rank: int = 16
    lr: float = 1e-4
    max_steps: int = 1200
    batch_size: int = 1
    grad_accum: int = 4
    resolution: int = RESOLUTION
    seed: int = 42
    log_every: int = 50


def _load_dataset(images_dir: Path, tokenizer, resolution: int):
    import torch
    from datasets import load_dataset
    from torchvision import transforms

    ds = load_dataset("imagefolder", data_dir=str(images_dir), split="train")
    tf = transforms.Compose(
        [
            transforms.Resize(resolution, interpolation=transforms.InterpolationMode.BILINEAR),
            transforms.CenterCrop(resolution),
            transforms.ToTensor(),
            transforms.Normalize([0.5], [0.5]),  # -> [-1, 1]
        ]
    )

    def preprocess(batch):
        batch["pixel_values"] = [tf(im.convert("RGB")) for im in batch["image"]]
        batch["input_ids"] = tokenizer(
            batch["text"], max_length=tokenizer.model_max_length,
            padding="max_length", truncation=True, return_tensors="pt",
        ).input_ids
        return batch

    ds.set_transform(preprocess)

    def collate(examples):
        return {
            "pixel_values": torch.stack([e["pixel_values"] for e in examples]),
            "input_ids": torch.stack([e["input_ids"] for e in examples]),
        }

    return ds, collate


def train_style(style: Style, config: Config, tcfg: TrainConfig) -> Path:
    import torch
    import torch.nn.functional as F
    from diffusers import (
        AutoencoderKL,
        DDPMScheduler,
        StableDiffusionPipeline,
        UNet2DConditionModel,
    )
    from diffusers.utils import convert_state_dict_to_diffusers
    from peft import LoraConfig
    from peft.utils import get_peft_model_state_dict
    from torch.utils.data import DataLoader
    from transformers import CLIPTextModel, CLIPTokenizer

    images_dir = config.dataset_dir / style.slug / "images"
    if not (images_dir / "metadata.jsonl").exists():
        raise FileNotFoundError(
            f"{images_dir}/metadata.jsonl missing — run build_dataset + caption for "
            f"{style.slug} first."
        )

    model = config.model_id
    if Path(model).is_file():
        raise ValueError(
            "Training needs an HF repo id or a diffusers folder, not a single "
            f".safetensors file ({model}). Set PROMPTLE_SD_MODEL accordingly."
        )

    device, dtype = config.device, config.torch_dtype
    torch.manual_seed(tcfg.seed)

    tokenizer = CLIPTokenizer.from_pretrained(model, subfolder="tokenizer")
    text_encoder = CLIPTextModel.from_pretrained(model, subfolder="text_encoder")
    vae = AutoencoderKL.from_pretrained(model, subfolder="vae")
    unet = UNet2DConditionModel.from_pretrained(model, subfolder="unet")
    noise_scheduler = DDPMScheduler.from_pretrained(model, subfolder="scheduler")

    # Freeze everything; LoRA params are added trainable below.
    for m in (vae, text_encoder, unet):
        m.requires_grad_(False)

    unet.add_adapter(
        LoraConfig(
            r=tcfg.rank,
            lora_alpha=tcfg.rank,
            init_lora_weights="gaussian",
            target_modules=["to_k", "to_q", "to_v", "to_out.0"],
        )
    )

    # Base weights in low precision; LoRA params stay fp32 for stable training.
    vae.to(device, dtype=dtype)
    text_encoder.to(device, dtype=dtype)
    unet.to(device)
    lora_params = [p for p in unet.parameters() if p.requires_grad]
    for p in lora_params:
        p.data = p.data.float()

    optimizer = torch.optim.AdamW(lora_params, lr=tcfg.lr)
    ds, collate = _load_dataset(images_dir, tokenizer, tcfg.resolution)
    loader = DataLoader(ds, batch_size=tcfg.batch_size, shuffle=True, collate_fn=collate)

    unet.train()
    step = 0
    accum = 0
    optimizer.zero_grad()
    print(f"[{style.slug}] training: {len(ds)} imgs, {tcfg.max_steps} steps, rank {tcfg.rank}")
    while step < tcfg.max_steps:
        for batch in loader:
            pixel_values = batch["pixel_values"].to(device, dtype=dtype)
            with torch.no_grad():
                latents = vae.encode(pixel_values).latent_dist.sample()
                latents = latents * vae.config.scaling_factor
                enc = text_encoder(batch["input_ids"].to(device))[0]

            noise = torch.randn_like(latents)
            bsz = latents.shape[0]
            timesteps = torch.randint(
                0, noise_scheduler.config.num_train_timesteps, (bsz,), device=device
            ).long()
            noisy = noise_scheduler.add_noise(latents, noise, timesteps)

            pred = unet(noisy, timesteps, enc).sample
            if noise_scheduler.config.prediction_type == "v_prediction":
                target = noise_scheduler.get_velocity(latents, noise, timesteps)
            else:
                target = noise
            loss = F.mse_loss(pred.float(), target.float()) / tcfg.grad_accum
            loss.backward()
            accum += 1

            if accum == tcfg.grad_accum:
                optimizer.step()
                optimizer.zero_grad()
                accum = 0
                step += 1
                if step % tcfg.log_every == 0 or step == 1:
                    print(f"  step {step}/{tcfg.max_steps}  loss={loss.item()*tcfg.grad_accum:.4f}")
                if step >= tcfg.max_steps:
                    break

    config.lora_dir.mkdir(parents=True, exist_ok=True)
    unet_lora_state = convert_state_dict_to_diffusers(get_peft_model_state_dict(unet))
    StableDiffusionPipeline.save_lora_weights(
        save_directory=str(config.lora_dir),
        unet_lora_layers=unet_lora_state,
        weight_name=f"{style.slug}.safetensors",
    )
    out = config.lora_dir / f"{style.slug}.safetensors"
    print(f"[{style.slug}] saved LoRA -> {out}")
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--style", help="style slug")
    g.add_argument("--all", action="store_true")
    ap.add_argument("--model", help="override base model (HF id or diffusers folder)")
    ap.add_argument("--rank", type=int, default=16)
    ap.add_argument("--lr", type=float, default=1e-4)
    ap.add_argument("--steps", type=int, default=1200)
    ap.add_argument("--batch-size", type=int, default=1)
    ap.add_argument("--grad-accum", type=int, default=4)
    args = ap.parse_args()

    config = Config()
    if args.model:
        config.model_id = args.model
    config.ensure_dirs()
    tcfg = TrainConfig(
        rank=args.rank, lr=args.lr, max_steps=args.steps,
        batch_size=args.batch_size, grad_accum=args.grad_accum,
    )
    styles = list(STYLES) if args.all else [by_slug(args.style)]
    if styles == [None]:
        raise SystemExit(f"Unknown style slug {args.style!r}")
    for st in styles:
        train_style(st, config, tcfg)


if __name__ == "__main__":
    main()
