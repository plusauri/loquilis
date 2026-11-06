# Conversation model and storage

- Status: **accepted in shape** 2026-11-03 (Dan). The protos are in `loquilis-model/protobuf/loquilis/model/v1/`, `loquilis-model` and `loquilis-agents` (Claude) are built and tested. `loquilis-store` and `loquilis-views` are built and tested. `LiveChat` records a running agent, and `app` is a thin window for talking to one, over the default library, another library, or a recorded take.
- Rule this works under: `doc/decisions/0001-dependency-direction.md` (no dependency on Tutilis).

## What the model has to do

Loquilis records conversations with coding agents and shows them in several views. The model is the product. The views are skins over it, so it has to be right before any UI exists.

### Loquilis's own needs

| # | Need | What it implies for the model |
|---|---|---|
| L1 | Every message, both sides, and every command the agent runs is kept | Records are append-only. Nothing the agent did is dropped when the record is normalized. |
| L2 | **Every command the app sends to the agent** is kept too: interrupts, permission answers, model switches, restarts | Commands from the app are entries in the record, as messages are. |
| L3 | Very large conversations, not held in memory | The model is split into a small **skeleton** that is always loaded and **bodies** fetched on demand. Large content goes in blobs. |
| L4 | Fast | Opening, scrolling and appending each touch only what is on screen. Nothing ever scans the whole conversation. |
| L5 | A tree view | Entries have parents. A conversation can branch (edit and resend, fork from a turn), and a turn holds nested work (tool calls, subagents). |
| L6 | Search across every conversation | One full-text index over messages, tool inputs and tool outputs, for every conversation in the library. |
| L7 | Live streaming | A turn grows while you watch. The model has an in-progress state, and partial text is a first-class entry state. |
| L8 | Many agents | One adapter per agent, each normalizing its stream into the same entries. Claude and Grok hold a live session on stdin; Codex may resume one process per turn. |
| L9 | A library others build on, from other languages too | Core types stay small and general, and they are defined in a language-neutral schema. Bespoke data goes in typed extensions. |

### Tutilis's needs, read from `:cogilis-records` (schema 1)

These are requirements, not code. A Cogilis take must import into Loquilis with nothing lost.

| # | Cogilis has | Loquilis provides it as |
|---|---|---|
| T1 | `Conversation`: id, start and end, agents (cli, version, model, door, `live`/`resume`), cost, outcome | `Conversation`, `AgentBinding` (`Transport`), `Outcome`, `Usage`. The door goes in an extension. |
| T2 | `Turn`: the client message, then Cogilis's reply | A derived `TurnSummary`: one `UserMessage`, and everything up to its `TurnResult`. |
| T3 | `Step` kinds: `narration`, `command`, `read`, `write`, each with a timestamp | `AgentText` (`TEXT_ROLE_NARRATION`), and `ToolCall` with `ToolKind` (`SHELL`, `FILE_READ`, `FILE_WRITE`, `FILE_EDIT`, …) plus `target_path`. Every entry has `at`. |
| T4 | A command's full output in `calc/`, its first lines inline | `ToolResult.output` as `Content` holding a `BlobRef` plus `head`. |
| T5 | The raw stream-json, gzipped, kept as the source of truth | The raw-event log, referenced from each entry by `RawRange`. |
| T6 | Turn duration, cost, agent turn count, error flag | `TurnResult`. |
| T7 | Attachments (a plan file sent with a message) | `UserMessage.attachments`. |
| T8 | Figures and answer files | **`Artifact`** (core): `ARTIFACT_KIND_IMAGE` / `HTML` / `FILE`, with `workspace_path`, `produced_by_call_id`, and `change`. |
| T9 | Engine calls, disclaimer flag, emotion, beat id, asks-for, character, lesson, plan, script, panel, door | **Extensions**: messages in Tutilis's own `.proto` (`tutilis.cogilis.v1.*`), held in `Extension`. None of them is core. |
| T10 | A `schema` version and tolerant reads | Protobuf evolution rules, `buf breaking`, and `ExportManifest.schema`. |
| T11 | Derived renderings (`transcript.md`, player timeline) are pure functions over the record | The views and exporters read through the same model API, and nothing derived is stored as truth. |
| T12 | Thinking blocks are dropped, because Cogilis records are public | Loquilis keeps thinking as `Thinking` with `VISIBILITY_PRIVATE`. It is hidden by default and dropped by exports unless asked for. In the recorded streams, most thinking blocks are empty (a signature only), and those make no entry. |

## Modeling: protobuf is the source of truth

As in Tutilis, the model is **defined in `.proto` files** and Kotlin is generated from them by Wire. A thin hand-written Kotlin layer adds the conveniences, and the database is the stored side. The reasons:

- **Other languages can take part.** The same files generate TypeScript (a web viewer, the Cogilis player), Python (analysis scripts), Swift and Go, with no second hand-written model to drift.
- **The data lasts for years.** Conversations live in libraries and in git records repos. Protobuf's rules (never reuse a field number, mark retired fields `reserved`, add fields freely) are the discipline that kind of data needs.
- **Extensions are typed and travel across languages.** An app built on Loquilis defines its own messages and attaches them as `Extension` (a type URL and the encoded bytes). Loquilis stores them and round-trips them without reading them, and any language that has the extending app's `.proto` can decode them.

### The files

All in `loquilis-model/protobuf/loquilis/model/v1/`, package `loquilis.model.v1`, Kotlin package `loquilis.model`.

| File | Messages |
|---|---|
| `conversation.proto` | `Conversation`, `AgentBinding` + `Transport`, `Outcome` + `OutcomeStatus`, `Usage`, `ParserRef`, `Branch` |
| `entry.proto` | `Entry` (header) and `EntryBody` (a `oneof` of the kinds below), plus `Author`, `EntryState`, `Visibility`, `RawRange` |
| `content.proto` | `Content` (a `oneof`: inline text or a `BlobRef`, plus a `head`), `BlobRef`, `Attachment` |
| `extension.proto` | `Extension`: an app's own message, held opaquely |
| `command.proto` | `AppCommand` (a `oneof`: `Interrupt`, `PermissionDecision`, `SetModel`, `SetPermissionMode`, `Restart`, `Fork`) and `PermissionRequest` |
| `view.proto` | Derived: `TurnSummary`, `SearchHit` |
| `export.proto` | `ExportManifest`, and the layout of the export folder |

### The entry kinds

| `EntryBody` kind | Author | What it is |
|---|---|---|
| `UserMessage` | user | A message the user sent, with attachments. It starts a turn. |
| `AgentText` | agent, or system | Text the agent wrote: `NARRATION` between tool calls, or the `REPLY` that ends the turn. `INJECTED` is text the harness put in, such as an expanded skill prompt. |
| `Thinking` | agent | The agent's reasoning, private by default. |
| `ToolCall` | agent | A tool the agent called: the tool's own name, its `ToolKind`, the exact input, the target path for file tools, and the agent's description of the call. |
| `ToolResult` | tool | Its full output, the error flag, how long it took, and any images the tool returned. Its parent is the `ToolCall`. |
| `Artifact` | tool | A chart, table, image, document or file the agent made. Its parent is the `ToolCall` that made it. |
| `SubAgent` | agent | A nested run. The subagent's entries are its children. |
| `AppCommand` | app | Anything the app sent other than a message. |
| `PermissionRequest` | system | The agent asking whether it may do something. A `PermissionDecision` answers it. |
| `TurnResult` | system | The end of a turn: duration, usage, model round trips, error. |
| `Notice` | system or app | A rate limit, a retry, a warning, a lost process. |

**Why one entry type and not Turn → Step nesting.** The tree view, search, streaming and paging all work over one ordered, parent-linked stream. Nesting would make each of them walk a different shape. Turns are derived (`TurnSummary`), and the store keeps them up to date as entries arrive, so views still get turns cheaply.

**Why `Entry` and `EntryBody` are separate messages.** The header (`id`, `seq`, `parent_id`, `at`, `author`, `state`, `visibility`) is what paging, the tree and search read. In the store, those fields are columns and only `EntryBody` is encoded as bytes, so a view never decodes a body it does not show. In an export, the whole `Entry` is written.

### Why `Extension` and not `google.protobuf.Any`

The first draft used `Any`. A test found the flaw: proto3-JSON writes an `Any` by expanding it, which needs the message's descriptor, so a reader without the type cannot write it at all. A plain Loquilis exporting a conversation that carries another app's data would fail, and the same rule binds every language's JSON library. `Extension` has `Any`'s field numbers, so its binary form is identical (`ExtensionsTest` pins this), and in JSON its value is base64 that any reader can carry.

**The cost:** an export shows an extension as base64, not as readable fields, so a git diff of a records repo will not show a change inside one. An app that wants readable records can write them out itself, knowing its own types, as Cogilis already writes `transcript.md`.

### Modeling conventions

- **Ids are ULIDs** in `string` fields: sortable by time and unique across libraries.
- **Times are `google.protobuf.Timestamp`**, and durations are `google.protobuf.Duration`. Wire maps them to `Instant` and `Duration`.
- **Money is `int64 cost_micro_usd`**, so totals add up exactly.
- **Every enum's zero value is `*_UNSPECIFIED`**, so an unset field never reads as a real value.
- **An entry names the agent's own tool name and also Loquilis's reading of it** (`tool` and `ToolKind`). A wrong reading loses nothing.
- **Field 15 is `extensions`** on `Conversation`, `Entry` and `Branch`, by convention.

### The Kotlin layer

Wire generates immutable classes. The hand-written layer is small, and it lives in `loquilis-model` beside the generated code:

- **A sealed view over `EntryBody`.** Wire represents a `oneof` as nullable fields, so the layer adds `val EntryBody.kind: EntryKind`, which lets code use an exhaustive `when`. `EntryKind.Unknown` is a real case: a kind from a newer schema decodes to it and keeps its bytes. `AppCommand.kind` does the same for commands.
- **Typed extension access.** `ExtensionKey<M>` gives `entry.ext(key)` and `entry.withExt(key, value)` over the `Extension` list.
- **Id value classes and a ULID factory.**
- **`Content` helpers**, such as `textOrNull()` and `isStored`.

Domain logic goes in extension functions, not in wrapper classes, as in Tutilis. The generated Kotlin is committed, as in Tutilis's `:modeling`, so a schema change shows up in review as a code diff.

### Schema discipline

- `buf lint` (STANDARD rules) and `buf breaking` against `main` (WIRE_JSON: breaking either the binary or the JSON form fails) run in CI. The config is in `buf.yaml`. CI is `.github/workflows/ci.yml`.
- Field numbers are never reused, and a retired field is marked `reserved`, with its name.
- A change that alters how entries are parsed from the same raw events bumps `ParserRef.version`. A change to the export layout bumps `ExportManifest.schema`.

## Raw events are the truth

Every line an agent emits is stored verbatim in the agent's own format, compressed in blocks. These lines are not protobuf on purpose: they are what the protos are parsed *from*. Entries name the raw lines they came from (`RawRange`). If a parser is wrong, or an agent's format changes, the entries can be rebuilt from raw events (`reparse`), and `ParserRef` says which records a parser fix would change.

## Storage

**One SQLite library file** holds all conversations, so search runs across them (L6). It uses WAL mode, so the UI can read while an agent writes. On the JVM, the driver is `org.xerial:sqlite-jdbc`, the one Tutilis ships, behind a `Store` interface. If size or a target ever calls for a different engine, only the `Store` implementation changes.

Unlike Tutilis's dossier store, which keeps one proto3-JSON document per plan, Loquilis has a huge number of small entries that are paged and searched constantly. So **header fields are columns, and bodies are proto binary**. People read and diff the exports, which stay proto3-JSON text.

| Table | Holds | Note |
|---|---|---|
| `conversation` | the `Conversation` header as columns, plus `extensions` as bytes | One row per conversation. |
| `entry` | header columns: id, conversation, seq, parent, at, author, kind, state, visibility, raw range. `body` holds the `EntryBody` bytes, and `extensions` holds bytes. | Indexed on `(conversation, seq)` and `(parent)`. |
| `turn` | `TurnSummary` as columns | Kept up to date on append. Rebuildable from entries. |
| `branch` | `Branch` as columns | |
| `raw_event` | conversation, seq range, encoding, lines | The newest lines sit one to a row, so each is durable when written. They fold into compressed blocks of about 256 KB. |
| `blob` | sha256, size, media type, compressed bytes *or* a file path | Content-addressed and deduplicated. Content up to 4 KB stays inline in the entry, and blobs over 1 MB are stored as files beside the db. Both thresholds are to be tuned against real tool outputs. |
| `entry_fts` | FTS5 over user text, agent text, tool input, tool output | Contentless: it keeps tokens only. Bodies are proto bytes, so SQLite has no text column to reference, and a stored copy would double the library. Snippets are made from the entry when a hit is shown. Private entries are not indexed. |

A test asserts that the columns match the protos, as `DossierSchemaTest` does in Tutilis.

**Paging (L3, L4).** A view asks for `turns(conversation, branch, range)` and gets `TurnSummary` rows. It asks for bodies (`entry(id)` and `blob(ref)`) only when something is on screen or opened. The client cache is a bounded LRU of bodies. Nothing loads a whole conversation, and the memory a conversation uses stays the same no matter how long it is.

**Append path (L7).** The adapter writes the raw line first, then upserts the entry. While text streams, a `STREAMING` entry is updated in place, throttled to about 10 Hz on disk and kept live in memory. `Recorder` commits a line and its entries together, so a crash cannot leave an entry without its line. A live session writes the two separately, so `recover` replays the raw log through a fresh parser, keeps existing ids, and closes tool calls left open as interrupted.

**Export.** A folder holding `manifest.json`, `entries.jsonl`, `blobs/` and `raw.jsonl.gz` (`export.proto`). That folder is what a git records repo such as `plusauri/cogilis-conversations` would hold. The folder, not the SQLite file, is the interchange format. An export leaves out private entries unless asked. Their raw lines stay as blank lines, so thinking never leaks through the raw log and every other entry's `RawRange` still holds.

## Libraries

There is one default library, and **File → Open Library** opens another, as in Tutilis.

## Modules

| Module | Depends on | Holds |
|---|---|---|
| `loquilis-model` | Wire runtime only | The `.proto` files, the generated Kotlin, and the thin Kotlin layer. Pure, `commonMain`. |
| `loquilis-store` | `loquilis-agents`, sqlite | `Store` (append, page, search, blobs, raw events), migrations, export/import, and `Recorder`/`recover`, which need the parser seam. |
| `loquilis-agents` | `loquilis-model` | The `AgentAdapter` seam, `ClaudeStreamParser` (stream-json on stdin) and `GrokAcpParser` (`grok agent stdio`, JSON-RPC), each with `ProcessSession` running the process and a test of its stream. |
| `loquilis-views` | `loquilis-store`, Compose | `ConversationPager` (turn summaries in pages, a bounded set of decoded turns), `ConversationState`, and the Messages view (user on the right, agent on the left), the Tree view, the Search view and the inspector. |
| `app` | all of the above | A window, the library picker, and settings. Kept thin. |

## Agents

`AgentParser` turns one agent's stream into entries, a line at a time, so a live turn and a re-parse of stored raw events run the same code. It gives `Append` and `Update` outputs, because an entry can change after it is written: a tool call completes, and text becomes the reply only when the turn ends. `ContentFactory` decides inline or blob for every piece of text, so no adapter can disagree about the threshold.

`LiveChat` is what a host talks to. It starts the agent on the first message, writes what the session produces on one thread, and starts the agent again on the next message after it stops, with `--resume` and the parser and raw numbering carried on from what is stored. A last turn with no result means a crash, so `recover` runs first. Views only read the store: they read the conversation again at most ten times a second while a chat writes.

A reply is read as GitHub-flavored Markdown by `org.jetbrains:markdown` and laid out by our own `MarkdownView`, off the main thread with the rest of its turn. Only what a browser or mail app would open becomes a link, since the agent writes them. The inspector, the Tree and search keep showing the text as stored.

Copying has two levels. `CopyButton` is the one way to copy a whole item, and it sits on each section of the inspector, so the stored text comes out whole with its marks. Text in a bubble can be dragged over and copied with the usual key. A bubble takes its click through `tapWithoutDrag`, because `clickable` swallows the drag that selection needs.

A permission request is an entry, and so is the answer, as an `AppCommand`. `ParseOutput.Ask` carries each possible answer as the exact line to send, so a host offers the choices without knowing the agent's protocol. "Always" passes back the rules the agent suggested, which `claude` writes to the workspace's `.claude/settings.local.json`. App commands are not in the raw log, so `recover` replays them from their entries, as it does user messages.

`ClaudeLiveSession` runs one `claude` process with messages on stdin, and it emits each raw line before what was parsed from it, which is the order the store must keep. It sends one message per turn: a message sent mid-turn waits, and it is recorded when it goes to the agent. Otherwise a turn's entries would interleave with the next one's. Messages that never went out are reported in `Ended.unsent`.

**What Claude's stream showed** (Claude Code 2.1.x, read across 80 turns of recorded sessions):

- The user's message is not echoed, so the app records it.
- One API message arrives as several `assistant` events, one block each.
- Which text is the reply is only known at the `result`.
- `total_cost_usd` is cumulative over a session, while `usage` covers one turn.
- Tool results can hold images, which the parser sends to the blob store.
- `tool_progress`, `thinking_tokens`, `task_*` and allowed `rate_limit_event` lines are telemetry. They stay in the raw log and make no entry.

### Known gaps

- **Artifacts are not captured yet.** The stream says a tool ran, not what files it left behind. Cogilis finds figures by looking at the workspace, so Loquilis needs a workspace watcher, or the Tutilis-side importer has to supply them. Until then, nothing creates `Artifact` entries, and T8 is unproven. The fixture take has no figures.
- **Only interrupts and permission answers are sent as `AppCommand`s.** `SetModel`, `SetPermissionMode`, `Restart` and `Fork` stay unused.
- **Cost after `--resume` is unproven.** The parser takes each turn's cost as the change in `total_cost_usd`, which starts from zero for each process. If a resumed process reports a total that includes earlier sessions, that process's first turn is overcounted.
- **The conversation list shows the newest 500.** Search still reaches the rest.
- **Text does not stream by the word.** The session gets whole blocks. Word-by-word text needs `--include-partial-messages` and handling for its events.
- **Attachments are not sent.** `send` takes text only.
- **Subagents are tested on hand-written lines only.** No recorded stream has one.
- **Turns cover the main branch only.** Forked entries store and export, but no turn rows are kept for a fork until branching is built.
- **A turn is read whole.** Views page by turn, so one turn of very many entries is decoded all at once when shown. Laying out one turn at a time also means a tool result arriving in a later turn than its call shows on its own line.
- **`recover` assumes the parser wrote every entry but user messages.** An app-written entry such as an `AppCommand` will need handling there once commands are sent.
- **Grok runs live, not per turn.** The plan was to resume one process per turn, but `grok agent stdio` holds a session, asks permission, cancels and resumes. `AgentParser` and `AgentSession` needed no change. Text arrives in chunks, which become one entry when the run of chunks ends. Each session sets `yoloMode` false, so Grok asks here even when the person's own settings approve everything.

## Tests that prove the model

- **Fixture round trip** (`aRecordedTakeRoundTripsToIdenticalFiles`): a recorded transcript goes raw → entries → export → import → export, and the two exports are identical files. Claude only until the Grok adapter exists.
- **Cogilis parity** (`ClaudeParityTest`): a real Cogilis take, replayed from its raw stream, has every turn, step, input, output head, reply, duration, cost and timestamp its record holds. This is the proof for rule 0001. Figures and the Cogilis-only extensions are not covered yet.
- **A real session** (`theRealClaudeKeepsContextAcrossMessages`, `LOQUILIS_LIVE=1`): one short exchange with the real `claude`, opt-in because it costs money.
- **Scale** (`ScaleTest`, on a 256 MB heap): a synthetic conversation of 100k entries and 1 GB of output. Opening it, paging from the middle and searching it each stay within a time budget asserted in the test. Normal runs use a small size, and `LOQUILIS_SCALE=1` runs the full one.
- **Crash** (`CrashTest`): a writer process is killed mid-stream, and after `recover` the library matches an uninterrupted run over the same lines.
- **Schema:** `buf lint` and `buf breaking`, plus the column-to-proto check.

## Decided 2026-11-03

1. **Thinking is kept**, as `VISIBILITY_PRIVATE`. It is hidden by default and left out of exports unless asked for.
2. **Branching is modeled now** (`parent_id`, `Branch`, `Fork`). The branching UI comes later.
3. **One default library**, with File → Open Library.
4. **Blob thresholds:** content up to 4 KB stays inline, and blobs over 1 MB are stored as files. Both are to be tuned against real tool outputs.
5. **Storage is SQLite** (`sqlite-jdbc`) for now, behind `Store`. It can be upgraded if size requires it.
6. **Protobuf is the source of truth** for the model, as in Tutilis. Kotlin is generated by Wire, with a thin hand-written layer on top. The store keeps header columns and binary bodies, and exports are proto3-JSON.
7. **Extensions are our own `Extension` message**, not `google.protobuf.Any`, so an export never depends on knowing another app's types.
