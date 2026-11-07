# Project guide for Loquilis

Loquilis is a desktop console and a Kotlin Multiplatform library (`loquilis`) for conversations with AI coding agents. See `README.md`.

## Hard rules

- **Loquilis depends on nothing in Tutilis.** That means no Gradle dependency, no composite build, no copied code, and no paths into a Tutilis checkout. Tutilis may depend on Loquilis later, never the reverse. Concepts specific to Tutilis go in typed extensions, never in core types. Full decision: `doc/decisions/0001-dependency-direction.md`.
- **No private data in this repo.** It will go public. Fixtures come from public sources only (for example, Cogilis takes on lesson plans).
- **American English** in code, comments and docs.
- **Markdown is not hard-wrapped.** One line per paragraph or bullet.

## Design

- Conversation model and storage: `doc/design/conversation-model.md`.
- Decisions: `doc/decisions/`, numbered, one per file.
- Bespoke consoles are the point of the library: collapsible panels, theming, avatars and client-configurable keys are coming. New UI work follows `doc/decisions/0002-bespoke-consoles.md`.

## Version

`app/src/jvmMain/kotlin/loquilis/app/AppVersion.kt` is the one place the version is written. Raise `PATCH` and rewrite `LAST_CHANGE` with every change that reaches the window, however small, so a person can tell which code they are looking at. The card appears on hover over the name at the top of the conversation list.

## Commands

Run from the repo root.

- Regenerate Kotlin after a `.proto` change: `./gradlew :loquilis-model:generateCommonMainProtos`. The generated code in `loquilis-model/src/commonMain/kotlin/loquilis/model/` is committed and is never hand-edited. Hand-written code lives in `loquilis/core/`.
- Test: `./gradlew :loquilis-model:jvmTest :loquilis-agents:jvmTest :loquilis-store:jvmTest :loquilis-views:jvmTest :app:jvmTest`.
- Full scale test, 100k entries and 1 GB, minutes: `LOQUILIS_SCALE=1 ./gradlew :loquilis-store:jvmTest --tests '*ScaleTest*' --rerun`.
- Window: `scripts/run-loquilis.sh` opens the default library, where real chats are kept, and sending a message runs the real `claude`. `scripts/run-loquilis.sh --sample` opens the bundled take. `scripts/run-loquilis.sh <take folder>` opens a folder holding `conversation.json` and `raw.jsonl.gz`, and `scripts/run-loquilis.sh <library.sqlite>` opens a library as it is. `:loquilis-views:jvmTest` also leaves pictures of it in `loquilis-views/build/review-shots/`.
- One real exchange with `claude`, which costs a little: `LOQUILIS_LIVE=1 ./gradlew :loquilis-agents:jvmTest --tests '*ClaudeLiveSessionTest*'`. A real conversation carried across two processes: `LOQUILIS_LIVE=1 ./gradlew :loquilis-store:jvmTest --tests '*LiveChatTest*'`. That includes the real Grok, which needs `grok` logged in. `LOQUILIS_SCALE_KEEP=<file>` leaves the scale test's library there, to open in the window.
- Schema checks: `buf lint` and `buf breaking --against '.git#ref=HEAD~1'`.
