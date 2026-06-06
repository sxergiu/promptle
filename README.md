# Promptle

> **The AI Telephone** — a real-time multiplayer party game where prompts become images, images become guesses, and guesses become chaos.

---

## How It Works

Each player writes a prompt → an AI generates an image → the next player guesses the original prompt from that image → repeat until the chain completes. At the end, everyone watches the full chain revealed entry by entry.

- **1–8 players** (single-player supported), fully synchronous
- **Server-authoritative timer** — rounds end for everyone simultaneously
- **No score** — the entertainment is watching the chain collapse

---

## Stack

- **Backend:** Spring Boot · WebSocket (STOMP) · JPA · Java 21
- **Frontend:** Angular 20 · Angular Material
- **Image generation:** pluggable — stub (dev) or ComfyUI (real AI images)

---

## Running Locally

```bash
# Backend
cd promptle && ./mvnw spring-boot:run

# Frontend
cd promptle-app && npm install && ng serve
```

Default config uses a stub image generator. Set `image.generation.provider=comfyui` in `application.properties` to use real AI images.

> **New here?** Full setup, architecture, and a docs map: [`promptle-docs/getting-started.md`](promptle-docs/getting-started.md) · [`promptle-docs/README.md`](promptle-docs/README.md).

---

## LAN Deployment (Docker + ComfyUI)

Play with friends on the same Wi-Fi using real AI-generated images.

**Prerequisites:** Docker Desktop (Apple Silicon) · ComfyUI with an SD 1.5 checkpoint

```bash
# 1. Start ComfyUI
./scripts/start-comfyui.sh

# 2. Start the app
docker compose build && docker compose up -d

# 3. Open http://promptle.local (or http://<mac-ip>)

# 4. Shut down
docker compose down -v
```

---

## License

[MIT](LICENSE)
