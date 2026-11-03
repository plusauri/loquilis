# Project guide for Loquilis

Loquilis is a desktop console and a Kotlin Multiplatform library (`loquilis-kit`) for conversations with AI coding agents. See `README.md`.

## Hard rules

- **Loquilis depends on nothing in Tutilis.** That means no Gradle dependency, no composite build, no copied code, and no paths into a Tutilis checkout. Tutilis may depend on Loquilis later, never the reverse. Concepts specific to Tutilis go in namespaced extensions, never in core types. Full decision: `doc/decisions/0001-dependency-direction.md`.
- **No private data in this repo.** It will go public. Fixtures come from public sources only (for example, Cogilis takes on lesson plans).
- **American English** in code, comments and docs.
- **Markdown is not hard-wrapped.** One line per paragraph or bullet.

## Design

- Conversation model and storage: `doc/design/conversation-model.md` (draft).
- Decisions: `doc/decisions/`, numbered, one per file.
