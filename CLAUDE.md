# CLAUDE.md — agent guide to Promptle

Promptle is **"The AI Telephone"**: a real-time multiplayer party game where a prompt becomes an AI image, the image becomes the next player's guess, and so on down a chain that's revealed at the end.

## Repo layout

| Path | What |
|---|---|
| `promptle/` | **Spring Boot backend** (Java 21, WebSocket/STOMP, JPA/Postgres). Source of truth for all game state. |
| `promptle-app/` | **Angular 20 frontend** (standalone components, signals, SSR). |
| `promptle-docs/` | **All design docs** — start at `promptle-docs/README.md`. |
| `scripts/` | Dev/deploy helpers (`dev.sh`, `deploy.sh`, `start-comfyui.sh`, `e2e/`). |
| `docker-compose.yml` | LAN deployment (backend + frontend + Postgres). |

## Run / build / test

**Backend** (`cd promptle`):
- Run: `./mvnw spring-boot:run` (needs Postgres — see Getting Started)
- Test: `./mvnw test`

**Frontend** (`cd promptle-app`):
- Dev server: `npm run PromptleUI` (= `ng serve`)
- Build: `npm run build`
- Test: `npm run test` (Karma/Jasmine; `*.spec.ts` co-located)

## Local development without ComfyUI

The image generator is pluggable via `image.generation.provider` in `promptle/src/main/resources/application.properties`:
- `stub` — returns a fixed image, **no AI backend needed** (use this for local dev / tests)
- `comfyui` — real images via a local ComfyUI (default; needs ComfyUI running on `:8188`)
- `python` — external HTTP service (stub only)

Storage is similarly pluggable (`image.storage.provider` = `local` | `s3`).

## Architecture in one breath

Backend owns game state and drives every phase transition (`LOBBY → PROMPTING → GENERATING → GUESSING → RESULTS`), broadcasting over STOMP topics. The frontend is a thin, signal-driven view that subscribes to those topics and renders the current phase. Round 1 images are text-to-image; rounds 2+ are image-to-image seeded from the previous image. Each chain gets a random art style. See the specs.

## Where to find things

| Looking for… | Read |
|---|---|
| How to run it end-to-end | `promptle-docs/getting-started.md` |
| Game rules / phases / single-player | `promptle-docs/specs/game-flow.md` |
| Backend layering, entities, services | `promptle-docs/specs/backend-*.md` |
| Frontend structure, components, responsive | `promptle-docs/specs/frontend-*.md` |
| WebSocket/STOMP + REST contract | `promptle-docs/specs/realtime.md` |
| Image pipeline (t2i/i2i, filtering, styles) | `promptle-docs/specs/image-generation.md` |
| What changed in the UI revamp | `promptle-docs/ui-revamp-changelog.md` |
| LAN deployment | `promptle-docs/deployment/` |

## Conventions

- **Frontend:** standalone components only (no NgModules); state via Angular **signals**; responsive via the `bp.*` mixins in `src/app/_breakpoints.scss` (no raw media queries); co-locate `*.spec.ts`.
- **Backend:** Controller → Service → Repository layering; phase transitions live in `GameService`; pluggable image gen/storage behind `image.*` interfaces.
- **Icons:** UI icons in `public/app-icons/` follow the `promptle-*` naming convention; player avatars in `public/player-icons/` must be registered in `core/models/player-icons.ts` to be selectable. **After adding a player avatar**, also run `scripts/gen-export-assets.sh` (needs `rsvg-convert`) to (re)generate the bundled `export-assets/player-icons/icon-N.png` the GIF export uses — the script reads `player-icons.ts` directly, so no other edit is needed.
- **The specs are the contract** — when you change behavior, update the matching doc in `promptle-docs/specs/`.

## Branches, commits, releases

The full procedure is [RELEASING.md](RELEASING.md) — read it before starting any feature or fix. In short:

- **Agents never commit, push, merge, tag or publish.** Prepare the tree, run the gates, hand the human the exact command.
- Releases are prototypes, `proto-N` (fixes `proto-N.M`), until Promptle goes live; then semver from `1.0.0`.
- Each item is a `feat/<slug>` branch off `proto-N`, PR'd into `proto-N` and squash-merged. At release, `proto-N` merges into `main` with a merge commit.
- Commits: `[area][topic] lowercase present-tense sentence` (areas: `backend`, `app`, `diffusion`, `e2e`, `deploy`, `ci`, `build`, `docs`). PR titles: `Feat: …` / `Fix: …` sentences.
- The gates are what `.github/workflows/ci.yml` runs: `./mvnw -B test` (JDK 21 + ffmpeg) and `npm ci --legacy-peer-deps && npm run build && npm run test:ci`.
- A release is a `release/proto-N` PR adding `releases/proto-N.md` and a `CHANGELOG.md` row, then `proto-N` → `main`, the tag `release-proto-N` (not `proto-N`, which is the branch), and the GitHub release `proto-N`, which pushes the `:proto-N` images to GHCR.
- `promptle-docs/` is gitignored, so anything GitHub or CI must see (release notes, process) lives at the repo root.
