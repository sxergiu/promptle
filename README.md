# Promptle 🎮

> **The AI Telephone** — a real-time multiplayer party game where prompts become images, images become guesses, and guesses become chaos.

---

## How It Works

Each player writes a prompt → an AI generates an image → the next player guesses the original prompt from that image → repeat until the chain completes. At the end, everyone watches the full chain revealed entry by entry and sees exactly how *"a dog riding a skateboard"* became *"a wolf on wheels"*.

```
You: "a wizard making coffee"
  → 🖼️  [AI image]
    → Player 2: "old man casting spell on mug"
      → 🖼️  [AI image]
        → Player 3: "grandpa doing a magic trick at Starbucks"
          → 🖼️  ...
```

- **2–8 players**, fully synchronous — everyone is always in the same phase
- **Server-authoritative timer** — the round ends for all players simultaneously
- **Skipped turns** fill in as *"Wise Hipiotic Cow"* and keep the chain alive
- **No score** — the entertainment is watching the chain collapse

---

## Screens

| Screen | What happens |
|---|---|
| **Home / Join** | Pick an avatar, set an alias, create or join a room |
| **Lobby** | Wait for friends, host starts the game |
| **Prompting** | Write your opening prompt, see who's ready |
| **Generating** | Watch the spinner while AI processes all images |
| **Guessing** | Guess the prompt behind the image in front of you |
| **Results** | Watch every chain unravel, entry by entry |

---

## Stack

**Backend** — Spring Boot · Spring WebSocket (STOMP) · JPA · async image pipeline
**Frontend** — Angular 20 (standalone + signals) · Angular Material · Bootstrap
**Image generation** — pluggable `ImageGenerationService` interface (ComfyUI implementation included)
**Realtime** — STOMP over WebSocket · server-side game state machine · `@TransactionalEventListener` broadcasts

---

## Running Locally

```bash
# Backend (Java 21+)
cd promptle
./mvnw spring-boot:run

# Frontend
cd promptle-app
npm install
ng serve
```

Configure image generation in `application.properties` — swap `StubImageGenerationService` for `ComfyUIGenerationService` when a ComfyUI instance is available.

---

## License

[MIT](LICENSE)
