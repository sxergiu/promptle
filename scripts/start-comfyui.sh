#!/bin/bash
# Launches ComfyUI Desktop from CLI with flags needed for Promptle deployment.
# Requires:
# 1. ComfyUI Desktop installed
# 2. ~/ComfyUI/.venv exists
# 3. comfyui-frontend-package installed.
# 4. macOS
~/ComfyUI/.venv/bin/python ~/ComfyUI-Installs/ComfyUI/ComfyUI/main.py --base-directory ~/ComfyUI --listen 0.0.0.0 --port 8188 --force-fp16
