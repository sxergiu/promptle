# Promptle proto-5

Back-filled: proto-5 was released on 2026-06-12, before this release process existed, on the tag
`proto` (GitHub release `proto-5`). No images were published for it; it was deployed by building
the checkout with `docker compose up --build`.

Against proto-4, this is what proto-5 added.

## Image-to-image chains (#34)

Rounds 2+ can generate from the previous round's image instead of from the guess alone, so a chain
drifts visually rather than starting over each round. Set
`IMAGE_GENERATION_CHAIN_MODE=image-to-image` to use it. **The shipped default is
`text-to-image`**: image-to-image keeps too much of the previous picture to follow a guess that
removes something from it.

Either way, every chain now ends on a generated image in the reveal, and inference was retuned —
CFG, image-to-image denoise and the art-style weight are configurable, and a generation timeout
that left late chains loading forever was raised above a full round's serial generation time.

## A random art style per chain (#32)

Each chain is given one art style at the start and keeps it for every image, so a chain is
recognisable end to end. Styles are seeded into the database on boot.

## Prompt filtering (#31)

Prompts are sanitised before they reach the image generator: blocked words are replaced, and the
ComfyUI workflow adds a negative prompt and a detection node.

## The UI revamp and single-player (#35)

Home, join, lobby, game and results screens are redesigned, with a new font and button styles.
A game can be started with one player, and each player returns to the lobby on their own after
the results. Every screen is laid out for phones as well as desktops (#33), and generation shows
an interactive pixel-grid loading screen (#30).

## Sounds, reconnection and the GIF export (#29, #37)

- **Sounds** for game events, with a toggle that is remembered.
- **A reconnection grace period**: a player who drops briefly keeps their seat, and the lobby
  roster stays in sync when they come back.
- **Export a chain as an animated GIF** from the results screen, redrawn with bundled avatar and
  icon assets.

## Tooling

An end-to-end screenshot and simulation project in `scripts/e2e` (#36), a local run guide, and
`scripts/dev.sh` with an interactive dev console.

## Known, and not fixed here

- **No persistence.** Postgres has no volume; `docker compose down` wipes every room.
- **No STOMP heartbeats.** A proxy that drops idle WebSockets (Cloudflare after ~100 s) can
  disconnect players during a long generating phase; the client reconnects after 5 s.
- **No accounts or rate limiting.** Anyone with the URL can create rooms and queue generation.
- **Serial generation.** ComfyUI renders one image at a time, so a full round takes minutes.
