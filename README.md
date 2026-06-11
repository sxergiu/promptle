# Promptle

> **The AI Telephone** — a real-time multiplayer party game where prompts become images, images become guesses, and guesses become chaos.

<p align="center">
  <img src="assets/demo.gif" alt="Promptle demo — pick an avatar, fill a 7-player lobby, write a prompt, watch the AI generate an image, guess the prompt, then see the chain revealed" width="760">
</p>

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

## Play Locally

**One command** brings up everything — Postgres (via Docker), the backend, and the frontend — using a built-in *stub* image generator, so **no AI backend (ComfyUI) is required**:

```bash
./scripts/dev.sh play
```

Then open **http://localhost:4200**, create a room, and play. Single-player works — you can start a game with just yourself. Press `Ctrl-C` in the terminal to stop everything.

**Prerequisites:** Java 21 · Node.js 20+ · Docker (only used to run Postgres). The script auto-detects a JDK 21 and starts Postgres for you; the first run also installs frontend dependencies.

The stub generator returns a fixed placeholder image instead of calling AI — perfect for trying the game flow. To play with **real AI-generated images**, see *LAN Deployment* below.

<details>
<summary>Prefer to run the pieces by hand?</summary>

```bash
# 1. Postgres on :5432 (db postgres / postgres) — any local Postgres works too
docker run -d --name promptle-dev-postgres \
  -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=postgres \
  -p 5432:5432 postgres:16-alpine

# 2. Backend — :8088  (stub images, no AI backend needed)
cd promptle && ./mvnw spring-boot:run -Dspring-boot.run.arguments=--image.generation.provider=stub

# 3. Frontend — :4200  (new terminal)
cd promptle-app && npm install && npm run PromptleUI
```

`./scripts/dev.sh` (no args) opens an interactive console for backend/frontend/tests/deploy/etc.

</details>

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
