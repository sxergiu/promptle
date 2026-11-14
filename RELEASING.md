# RELEASING.md — how a prototype gets out of here

Read [CLAUDE.md](CLAUDE.md) first. This is the procedure on top of it: how the work for a
prototype is split, branched, reviewed, merged, tagged, published and deployed. It follows
[harness](https://github.com/sxergiu/claude-harness) and
[logtower](https://github.com/sxergiu/logtower), with two differences: releases are numbered
prototypes rather than semver, and a prototype collects its items on its own branch before it
reaches `main`.

## Who runs what

**Agents never commit, push, merge, tag or publish in this checkout.** Every `git`, `gh pr merge`
and `gh release` line below that writes anything is the human's. An agent's job is to get the
tree, the notes and the branch into the state where running that line is the only thing left:
prepare, run the gates, hand over the exact command, stop.

Two steps stay the human's even where git is delegated, unless they are handed over explicitly
for that round:

- **The merge is the review.** Merging your own PR turns the PR into ceremony.
- **Publishing the GitHub release is the deploy trigger.** It builds and pushes the images a host
  pulls (§6), so it is outward-facing in a way a branch is not.

## What a release is here

A **prototype**, `proto-N`: one list of features and fixes decided up front, collected on the
`proto-N` branch, merged into `main` once, and tagged. A fix to a released prototype is
`proto-N.M`, made the same way.

| Name | What it is |
|---|---|
| `proto-6` | the branch the prototype's items merge into, kept afterwards as its record |
| `feat/<slug>` | one item, branched off and merged back into `proto-6` |
| `release/proto-6` | the release commit (§5), into `proto-6` |
| `release-proto-6` | the tag on `main` — not `proto-6`, which is the branch |
| `proto-6` | the GitHub release and the image tag |

The tag has its own name because a branch and a tag both called `proto-6` make the name
ambiguous to git: `git push origin proto-6` refuses outright.

Versions in `pom.xml` and `package.json` are not moved: `proto-6` is not a valid npm version, and
nothing reads them. Once Promptle goes live, releases switch to semver from `1.0.0`, and the
release commit starts moving those fields.

Prototypes before proto-6 predate this process. proto-5's tag is `proto` and its GitHub release
is `proto-5`; both are left as they are, and its notes are back-filled in `releases/proto-5.md`.

## 1. Split the prototype

Before any branch exists, write the list. A feature is something a player or a host can see
Promptle doing that it did not do before; a fix is something that was wrong. Group small fixes in
one area into one item — three unrelated one-liners across three files are one branch, not three.

Post the list before starting. It is the thing the human corrects cheaply, and the branch names
come straight off it. Then the prototype branch, from `main`:

```bash
git switch main && git pull --ff-only && git switch -c proto-6 && git push -u origin proto-6
```

**If the work is already sitting in the tree** as one pile of modified files, stop and ask
before committing anything. Collapsing it into one commit and splitting it into its items are
different work, and which one happens is the human's call. If you catch yourself about to write
*"this is one commit rather than per-item PRs"* into a summary, that sentence belonged in a
question asked before the commit.

## 2. One branch per item

`feat/<slug>` off `proto-N`, for a feature or for a group of fixes under one area.

**At least three commits on a feature branch**: the mechanism first, then the thing that uses it,
then the fixes found while using it. A feature that cannot be told as three steps is usually one
commit doing three things. A genuinely small fix — a wrong constant, a broken fixture — may be one
or two; if you are reaching for that exception to avoid splitting, split.

### Commit messages

`[area][topic] lowercase sentence saying what changed, in the present tense`

| Area | Covers |
|---|---|
| `backend` | `promptle/` — Spring Boot |
| `app` | `promptle-app/` — Angular |
| `diffusion` | `promptle-diffusion/` |
| `e2e` | `scripts/e2e/` |
| `deploy` | `docker-compose.yml`, Dockerfiles, `scripts/deploy.sh` |
| `ci` | `.github/workflows/` |
| `build` | dependencies, the release commit |
| `docs` | README, this file, `releases/` |

Topics are the subsystem: `game`, `lobby`, `realtime`, `image-gen`, `export`, `sound`, `styles`,
`publish`…

```
[backend][realtime] send stomp heartbeats so a long generating phase keeps its sockets
[app][lobby] keep the roster in sync after a reconnect
```

Say what it does, not what it is. The subject line is the only part most people read.

### The gates

Run before a branch is offered for review. They are exactly what [`ci.yml`](.github/workflows/ci.yml)
runs, so green here means green there:

```bash
cd promptle && ./mvnw -B test
```

```bash
cd promptle-app && npm ci --legacy-peer-deps && npm run build && npm run test:ci
```

The backend gate needs JDK 21 and `ffmpeg` on the PATH: the GIF export tests run it for real.
`--legacy-peer-deps` is there because the lockfile does not resolve without it (the Dockerfile
uses it too); aligning every `@angular/*` package to one version is what would let it go.

CI also builds both images on every pull request, without pushing them. A Dockerfile change is
the one thing the gates above cannot see, so build them locally if you touched one:

```bash
docker compose build
```

## 3. Pull request, then squash into the prototype

One PR per item, into `proto-N`.

**Title** — a descriptive sentence naming what the reader gets, prefixed `Feat:` or `Fix:`.
`[area][topic]` is the commit convention and does not belong here: a PR title is read in a list by
somebody deciding whether to open it.

```
Feat: Export a chain as a GIF from the results screen
Fix: Keep the lobby roster after a player reconnects
```

**Body** — documentation, not a story: what the change is, what it covers, what it deliberately
does not, and anything a reviewer has to check by hand (anything that needs ComfyUI, say, since CI
runs on the stub generator). State findings as facts; leave out the false starts.

**CI must be green.** **Merge by squash**, so `proto-N` gets one commit per item with the PR
number appended — GitHub takes the PR title as that subject, which is the one place the two
conventions meet:

```
Feat: Export a chain as a GIF from the results screen (#41)
```

The first two lines are an agent's to run; the merge is the human's. `--delete-branch` is passed
here and not set on the repository, because the repository setting would also delete `proto-N`
when it merges into `main`:

```bash
gh pr create --base proto-6 --head feat/<slug> --title "<subject>" --body-file <notes>
```

```bash
gh pr checks --watch
```

```bash
gh pr merge --squash --delete-branch
```

Check `gh auth status` before writing any `gh` line — a lapsed token fails every one of them, and
`gh auth login` is interactive, so it is the human's.

## 4. Clean up merged item branches

`--delete-branch` removes the remote branch. What is left is the local copy. Check before deleting
— a branch with commits not in `proto-N` is unfinished, not stale, and this is the one step here
that destroys work:

```bash
git log --oneline proto-6..feat/<slug>
```

Empty output means fully merged, and nothing else does. Then `-d`, never `-D`, so git refuses what
that check missed:

```bash
git branch -d feat/<slug>
```

`proto-N` branches are never deleted: they are the record of what each prototype was.

## 5. The release commit, then the prototype into main

One commit, subject `[build][publish] release proto-N, <what it is about>`, on `release/proto-N`
off `proto-N`, merged into `proto-N` by its own PR — so CI has run on the exact tree that goes to
`main`. It touches exactly two things:

1. **`releases/proto-N.md`** — the notes. One file per release, holding only what is new in it.
2. **`CHANGELOG.md`** — one new row in the index table, linking to that file. The index is not a
   second copy of the notes.

`git status` before the commit: a notes file that was never added is a dead link in the changelog
and an empty GitHub release.

Then one PR from `proto-N` into `main`, titled `proto-N`, with the notes as its body, **merged
with a merge commit** rather than squashed — that is what keeps each item's own commit on `main`:

```bash
gh pr create --base main --head proto-6 --title "proto-6" --body-file releases/proto-6.md
```

```bash
gh pr merge --merge
```

### Writing the notes

Written for somebody deciding whether to take this prototype and what it changes for them — a host
about to pull it, or a player wondering what's new.

- **One `##` section per item**, titled by its subject, with the PR number.
- **Lead the file with what a reader is most likely to be hit by** — a new required `.env`
  variable, a changed port, a wiped database — not the biggest diff.
- **Lead each section with what changed**, then what it affects, then the evidence. Not the order
  the work happened in.
- Say what was **measured** as stated fact. No first person, no debugging narrative.
- Say what was **not** fixed, in a "Known, and not fixed here" section.

## 6. Tag, GitHub release, images

The tag points at the merge commit on `main`, so this waits on §5. Check that the Actions run on
that commit is green first — a tag pushed ahead of a red run is a published prototype that does
not build.

```bash
git switch main && git pull --ff-only && git tag release-proto-6 && git push origin release-proto-6
```

Then the GitHub release, **named `proto-N`, with `releases/proto-N.md` as the body verbatim**. The
notes file, the release and the changelog row say the same words because there is one place to
write them.

```bash
gh release create release-proto-6 --title "proto-6" --notes-file releases/proto-6.md
```

**Publishing it runs [`release.yml`](.github/workflows/release.yml)**, which builds both images for
`linux/amd64` and `linux/arm64` and pushes them to GHCR:

```
ghcr.io/sxergiu/promptle-backend:proto-6    and :latest
ghcr.io/sxergiu/promptle-frontend:proto-6   and :latest
```

Watch that run. A release whose images failed to push is a prototype no host can deploy.

## 7. Deploy

A host runs released images; it does not build. `PROMPTLE_VERSION` in the host's `.env` pins the
prototype, so a deploy is one edit and a pull, and a rollback is the same edit back. Check out the
release tag too, so `docker-compose.yml` matches the images:

```bash
git fetch --tags && git checkout release-proto-6 && sed -i 's/^PROMPTLE_VERSION=.*/PROMPTLE_VERSION=proto-6/' .env && docker compose pull && docker compose up -d
```

Without `PROMPTLE_VERSION` the host follows `latest`. The full host setup is in
`promptle-docs/deployment/` (kept local).

`docker compose up --build` still works anywhere and builds from the checkout instead — that is
the local path, not the deploy path.

## The checklist

```
[ ] prototype split into a list of features and fixes, posted, corrected
[ ] proto-N branched from main and pushed
[ ] work already in the tree? asked before committing, never split it alone
[ ] each item on feat/<slug> off proto-N, >=3 commits (1-2 only for a genuinely small fix)
[ ] gates green locally on every branch
[ ] one PR per item into proto-N, title a sentence (Feat:/Fix:), body documentation not story
[ ] CI green
[ ] human squash-merges into proto-N with (#N), --delete-branch
[ ] merged feat/* branches deleted locally
[ ] release/proto-N into proto-N: releases/proto-N.md, CHANGELOG.md row
[ ] git status clean before the release commit
[ ] proto-N into main, merge commit
[ ] Actions green on the merge commit on main
[ ] release-proto-N tag pushed
[ ] GitHub release proto-N on that tag, body = releases/proto-N.md
[ ] release.yml green, both images on GHCR at proto-N
[ ] host: checkout release-proto-N, PROMPTLE_VERSION=proto-N, pull, up -d
```
