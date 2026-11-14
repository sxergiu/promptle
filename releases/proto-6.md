# Promptle proto-6

The first prototype published as images. Nothing in the game changes; what changes is how a host
gets Promptle and how a change gets checked before it lands.

## A host pulls the released images instead of building (#39)

`docker-compose.yml` now names the published images, for `linux/amd64` and `linux/arm64`:

```
ghcr.io/sxergiu/promptle-backend:${PROMPTLE_VERSION:-latest}
ghcr.io/sxergiu/promptle-frontend:${PROMPTLE_VERSION:-latest}
```

- **Deploying this prototype** is `PROMPTLE_VERSION=proto-6` in `.env`, then
  `docker compose pull && docker compose up -d`. Without `PROMPTLE_VERSION` a host follows `latest`.
- **`docker compose up -d --build` still builds the checkout**, now under those same image names,
  so a later `docker compose pull` replaces a local build with the published one.
- **Two optional `.env` overrides**: `COMFYUI_URL` (default `http://host.docker.internal:8188`) for
  a ComfyUI on another machine, and `PROMPTLE_HTTP_BIND` (default `80`), which a tunnelled host
  sets to `127.0.0.1:8080` so the app is reachable only through the tunnel. `.env` is gitignored.
- **Every service restarts `unless-stopped`**, so the stack comes back with Docker after a reboot.

Both Dockerfiles run their build stage on the builder's platform; only the runtime stage is built
per architecture.

## CI on every pull request (#39)

Every pull request, and every push to `main` and `proto-*`, runs the backend tests (256, JDK 21
with ffmpeg), the frontend build and specs (197, ChromeHeadless), and builds both images without
pushing them. A pull request that changes the release workflow also builds both platforms.

`ExportPreviewTest`, a dev tool that writes a GIF to `target/`, is excluded by default;
`-DexcludedGroups=` still runs it.

## The release process (#39)

`RELEASING.md` describes it: each item is a `feat/<slug>` branch squash-merged
into `proto-N`, which merges into `main` with a merge commit at release. The tag is
`release-proto-N`, and publishing the GitHub release `proto-N` pushes the `:proto-N` and `:latest`
images. proto-5's notes are back-filled in `releases/proto-5.md`.

## Known, and not fixed here

- **No persistence.** Postgres has no volume; `docker compose down` wipes every room.
- **No STOMP heartbeats.** A proxy that drops idle WebSockets (Cloudflare after ~100 s) can
  disconnect players during a long generating phase; the client reconnects after 5 s.
- **No accounts or rate limiting.** Anyone with the URL can create rooms and queue generation.
- **Serial generation.** ComfyUI renders one image at a time, so a full round takes minutes.
- **CI runs on the stub generator.** Nothing in it exercises ComfyUI.
