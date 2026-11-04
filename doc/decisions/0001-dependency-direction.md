# 0001: Loquilis depends on nothing in Tutilis

- Status: accepted 2026-11-03 (Dan)

## The rule

**Loquilis depends on no Tutilis code, artifact, repo, or data.** That covers no Gradle dependency, no composite build, no copied-in Tutilis package, no `tutil.*` import, and no path into a Tutilis checkout. Tutilis may come to depend on Loquilis later. The arrow points one way only: Tutilis → Loquilis.

**Loquilis is meant to serve Tutilis's conversation needs**, so it can become the home of Tutilis's conversations, the Cogilis recordings first. Tutilis's needs are treated as requirements, written down in Loquilis (`doc/design/conversation-model.md`). Tutilis code is never treated as a source to import.

## How it is applied

- The Loquilis model is written fresh. The Tutilis module `:cogilis-records` is read as a list of requirements, and no code is copied from it.
- Concepts that belong only to Tutilis (lessons, plans, characters, emotions, script beats, engine calls) are never core types. They travel as typed **extensions** on the core model: messages in Tutilis's own `.proto`, held opaquely by Loquilis.
- **The proof is a test, not a dependency.** Loquilis keeps a few real agent transcripts as test fixtures, and its tests show they import with nothing lost. They are copied in from public Cogilis takes on lesson plans, which are public teaching material. That test is how we know Loquilis can serve Tutilis.
- No fixture, example, or doc in this repo may contain private financial data. The same rule as in Tutilis applies, and it matters more here because this repo will go public.

## Trade-offs accepted

- **Duplication for a while.** `:cogilis-records` and the Loquilis model live side by side until Tutilis migrates, and the two can drift apart in the meantime. The fixture test catches drift on the Loquilis side.
- **Designing for two products.** The core has to stay general. A field only Tutilis needs goes in an extension, even if putting it in the core would be quicker.
- **Tutilis takes on a versioned dependency** once it migrates (Maven or a composite build). From then on, a Loquilis change can break Tutilis.
- **Existing takes need a converter**, or Loquilis must import `conversation.json` schema 1 directly. That importer belongs on the Tutilis side, or in an optional `loquilis-import-cogilis` module that reads only the JSON format and links to no Tutilis code.
