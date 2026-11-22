# Project guide for Loquilis

Loquilis is a desktop console and a Kotlin Multiplatform library (`loquilis`) for conversations with AI coding agents. See `README.md`, whose "Terms" section defines Tutilis, Cogilis and a take.

## Hard rules

- **Loquilis depends on nothing in Tutilis**, Plusauri's retirement-planning app, which is not open source. That means no Gradle dependency, no composite build, no copied code, and no paths into a Tutilis checkout. Concepts that belong to one embedding app go in typed extensions, never in core types. The published ui-kit is the one Plusauri dependency. Full decision: `doc/decisions/0001-dependency-direction.md`.
- **No private data:** fixtures and examples come from public sources only.
- **American English** in code, comments and docs.
- **Markdown is not hard-wrapped.** One line per paragraph or bullet.

## Design

- Conversation model and storage: `doc/design/conversation-model.md`.
- Decisions: `doc/decisions/`, numbered, one per file.
- Open models and showing where data goes are a core aim: `doc/decisions/0003-open-models-and-where-data-goes.md`.
- The library is for building bespoke consoles. New UI work follows `doc/decisions/0002-bespoke-consoles.md`.

## Version (for maintainers)

`app/src/jvmMain/kotlin/loquilis/app/AppVersion.kt` is the one place the version is written. The maintainer raises `PATCH` and rewrites `LAST_CHANGE` with every change that reaches the window, so a person can tell which code they are looking at. The card appears on hover over the name at the top of the conversation list. An outside pull request leaves it alone; the maintainer bumps it on merge.

## Commands

Run from the repo root.

- Regenerate Kotlin after a `.proto` change: `./gradlew :loquilis-model:generateCommonMainProtos`. The generated code in `loquilis-model/src/commonMain/kotlin/loquilis/model/` is committed and is never hand-edited. Hand-written code lives in `loquilis/core/`.
- Test: `./gradlew :loquilis-model:jvmTest :loquilis-agents:jvmTest :loquilis-store:jvmTest :loquilis-views:jvmTest :app:jvmTest`.
- Full scale test, 100k entries and 1 GB, minutes: `LOQUILIS_SCALE=1 ./gradlew :loquilis-store:jvmTest --tests '*ScaleTest*' --rerun`.
- Window: `scripts/run-loquilis.sh` opens the default library, where real chats are kept, and sending a message runs the real agent. `scripts/run-loquilis.sh --sample` opens the bundled take. `scripts/run-loquilis.sh <take folder>` opens a folder holding `conversation.json` and `raw.jsonl.gz`, and `scripts/run-loquilis.sh <library.sqlite>` opens a library as it is. `:loquilis-views:jvmTest` also leaves pictures of it in `loquilis-views/build/review-shots/`.
- One real exchange with `claude`, which costs a little: `LOQUILIS_LIVE=1 ./gradlew :loquilis-agents:jvmTest --tests '*ClaudeLiveSessionTest*'`. A real conversation carried across two processes: `LOQUILIS_LIVE=1 ./gradlew :loquilis-store:jvmTest --tests '*LiveChatTest*'`. That includes the real Grok, which needs `grok` logged in. `LOQUILIS_SCALE_KEEP=<file>` leaves the scale test's library there, to open in the window.
- Schema checks: `buf lint` and `buf breaking --against '.git#branch=main'`. CI runs the breaking check against the pull request's base branch, or against the previous push on `main`.
