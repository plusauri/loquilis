# 0001: Loquilis depends on nothing in Tutilis

- Status: accepted 2026-11-03

## Background

Tutilis is Plusauri's retirement-planning desktop app, and it is not open source. Cogilis is Plusauri's agent workspace for Tutilis, an AI coding agent that answers questions about Tutilis's lesson plans, and its recorded sessions (takes) on lesson plans are public teaching material. Loquilis was started partly to hold conversations like those, so Tutilis is the first app whose needs it was designed against.

## The rule

**Loquilis depends on no Tutilis code, artifact, repo, or data.** That covers no Gradle dependency, no composite build, no copied-in Tutilis package or import, and no path into a Tutilis checkout. Tutilis may come to depend on Loquilis later. The arrow points one way only: Tutilis → Loquilis.

**Loquilis is meant to serve an embedding app's conversation needs, with Tutilis as the first example**, so it can become the home of Tutilis's conversations, the Cogilis takes first. Those needs are written down in Loquilis as requirements (`doc/design/conversation-model.md`). Tutilis code is never treated as a source to import.

## How it is applied

- The Loquilis model is written fresh. Tutilis's own record format for takes, in Tutilis's private repo, was read as a list of requirements, and no code is copied from it.
- Concepts that belong only to one embedding app (for Tutilis: lessons, plans, characters, script beats, engine calls) are never core types. They travel as typed **extensions** on the core model: messages in the app's own `.proto`, held opaquely by Loquilis.
- **The proof is a test, not a dependency.** Loquilis keeps a real agent transcript as a test fixture, and its tests show it imports with nothing lost. It is a Cogilis take on a lesson plan, which is public teaching material.
- **No private data:** fixtures, examples and docs in this repo come from public sources only.

## Trade-offs accepted

- **Duplication for a while.** Tutilis's record format and the Loquilis model live side by side until Tutilis migrates, and the two can drift apart in the meantime. The fixture test catches drift on the Loquilis side.
- **Designing for more than one product.** The core has to stay general. A field only one app needs goes in an extension, even if putting it in the core would be quicker.
- **Tutilis takes on a versioned dependency** once it migrates (Maven or a composite build). From then on, a Loquilis change can break Tutilis.
- **Existing takes need a converter**, or Loquilis must import a take's `conversation.json` directly. Loquilis does the second for its fixture and sample (`recordTake` in `loquilis-store`), reading only the JSON format and linking to no Tutilis code. A fuller importer belongs on the Tutilis side.
