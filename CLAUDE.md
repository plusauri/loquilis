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

## Commands

Run from the repo root.

- Regenerate Kotlin after a `.proto` change: `./gradlew :loquilis-model:generateCommonMainProtos`. The generated code in `loquilis-model/src/commonMain/kotlin/loquilis/model/` is committed and is never hand-edited. Hand-written code lives in `loquilis/core/`.
- Test: `./gradlew :loquilis-model:jvmTest :loquilis-agents:jvmTest :loquilis-store:jvmTest :app:jvmTest`.
- Full scale test, 100k entries and 1 GB, minutes: `LOQUILIS_SCALE=1 ./gradlew :loquilis-store:jvmTest --tests '*ScaleTest*' --rerun`.
- Review window, read-only over a recorded take: `scripts/run-loquilis.sh`. `scripts/run-loquilis.sh <take folder>` opens another take, a folder holding `conversation.json` and `raw.jsonl.gz`. `:app:jvmTest` also leaves pictures of it in `app/build/review-shots/`.
- One real exchange with `claude`, which costs a little: `LOQUILIS_LIVE=1 ./gradlew :loquilis-agents:jvmTest --tests '*ClaudeLiveSessionTest*'`.
- Schema checks: `buf lint` and `buf breaking --against '.git#ref=HEAD~1'`.
