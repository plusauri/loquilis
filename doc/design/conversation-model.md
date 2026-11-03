# Conversation model and storage

- Status: **draft** 2026-11-03, for review. Nothing is built yet.
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
| L8 | Many agents | One adapter per agent, each normalizing its stream into the same entries. Claude holds a live session on stdin; Grok and Codex resume one process per turn. |
| L9 | A library others build on | Core types stay small and general. Bespoke data goes in typed extensions. |

### Tutilis's needs, read from `:cogilis-records` (schema 1)

These are requirements, not code. A Cogilis take must import into Loquilis with nothing lost.

| # | Cogilis has | Loquilis provides it as |
|---|---|---|
| T1 | `Conversation`: id, start and end, agents (cli, version, model, door, `live`/`resume`), cost, outcome | Core `Conversation` plus `AgentBinding`. Outcome and cost are core. |
| T2 | `Turn`: the client message, then Cogilis's reply | A core derived `Turn`: one user message, and everything up to the agent's turn result. |
| T3 | `Step` kinds: `narration`, `command`, `read`, `write`, each with a timestamp | Core entries `AgentText(role = Narration)` and `ToolCall` + `ToolResult`. A `ToolCall` carries a `ToolKind` hint (`Shell`, `FileRead`, `FileWrite`, `Mcp`, `Other`). |
| T4 | A command's full output in `calc/`, its first lines inline | `ToolResult.output` as a `BlobRef` that holds a short inline head. |
| T5 | The raw stream-json, gzipped, kept as the source of truth | Core `RawEvent` log, one row per line the agent emitted, with each entry pointing back to the raw lines it came from. |
| T6 | Turn duration, cost, agent turn count, error flag | Core `TurnResult` entry. |
| T7 | Attachments (a plan file sent with a message) | Core `Attachment` as a `BlobRef` on `UserMessage`. |
| T8 | Engine calls, figures, answer files, disclaimer flag, emotion, beat id, asks-for, character, lesson, plan, script, panel | **Extensions** under namespace `tutilis.cogilis`, defined in Tutilis. None of them is core. |
| T9 | `schema` version, tolerant reads | Core `SCHEMA` constant, `ignoreUnknownKeys`, and a migration table in the store. |
| T10 | Derived renderings (`transcript.md`, player timeline) are pure functions over the record | The views and exporters read through the same model API, and nothing derived is stored as truth. |
| T11 | Thinking blocks are dropped, because Cogilis records are public | Loquilis **keeps** thinking, flagged `private` so it is hidden by default. An exporter can drop it. |

## Core concepts

```
Library ─┬─ Conversation ─┬─ AgentBinding      (which cli, version, model, transport)
         │                ├─ Entry*            (append-only, each with a parent → a tree)
         │                ├─ Branch*           (named heads into the entry tree)
         │                └─ RawEvent*         (the agent's own lines, verbatim)
         └─ Blob*                              (content-addressed, shared across conversations)
```

### Entry: the one record type

Everything that happens is an `Entry`. There is a single table and a single sequence, and the kind says what the entry is.

```kotlin
data class Entry(
    val id: EntryId,              // ULID: sortable by time, unique across libraries
    val conversation: ConversationId,
    val seq: Long,                // dense, per conversation: the append order
    val parent: EntryId?,         // tree edge (null only for the root)
    val at: Instant,              // the agent's timestamp when it gives one, else arrival
    val author: Author,           // User, Agent, Tool, App, System
    val body: EntryBody,          // the sealed kinds below
    val state: EntryState,        // Streaming, Complete, Failed, Interrupted
    val rawRange: LongRange?,     // RawEvent seqs this entry was parsed from
    val ext: Extensions,          // namespaced extensions (see below)
)

sealed interface EntryBody {
    data class UserMessage(val text: Content, val attachments: List<Attachment>)
    data class AgentText(val text: Content, val role: TextRole)     // Narration, Reply
    data class Thinking(val text: Content)                          // private by default
    data class ToolCall(val callId: String, val tool: String, val kind: ToolKind, val input: Content)
    data class ToolResult(val callId: String, val output: Content, val isError: Boolean)
    data class SubAgent(val label: String)                          // a nested run; its entries are children
    data class AppCommand(val command: Command)                     // L2: Interrupt, Permission, SetModel, Restart, …
    data class TurnResult(val durationMs: Long?, val costUsd: Double?, val agentTurns: Int?, val isError: Boolean)
    data class Notice(val level: Level, val text: String)           // rate limits, retries, agent warnings
}

/** Short text sits inline. Anything over the blob threshold is a reference with a preview. */
sealed interface Content {
    data class Inline(val text: String)
    data class Stored(val blob: BlobRef, val head: String)          // head: first lines, for views and FTS snippets
}
```

**Why one type and not Turn → Step nesting.** The tree view, search, streaming and paging all work over one ordered, parent-linked stream. Nesting would make each of them walk a different shape. Turns are **derived**: a turn starts at a `UserMessage` and ends at its `TurnResult`. The store keeps a turn index (see below), so views still get turns cheaply.

### Branches

Editing and resending a message makes a new `UserMessage` whose parent is the edited message's parent. The two messages are siblings. A `Branch` is a named pointer to a leaf, and the main line is the branch the conversation was started on. A view shows one path from the root to a branch head. The tree view shows all of them.

Agents do not branch natively. A branch is resumed by replaying its path into a fresh session (the same way Cogilis's `--replay --through N` works), or, for `resume` agents, by forking the agent's session where it supports that. The model records which happened, as an `AppCommand(Fork)`.

### Extensions

```kotlin
class ExtensionKey<T>(val namespace: String, val serializer: KSerializer<T>)
// Tutilis, on its own side:
val CogilisTurnExt = ExtensionKey("tutilis.cogilis.turn", CogilisTurnData.serializer())
entry.ext[CogilisTurnExt]   // typed read; unknown namespaces round-trip as raw JSON
```

Extensions attach to `Conversation`, `Entry` and `Branch`. The core never reads them. They are stored as JSON and survive a round trip even when the reading app does not know the namespace. An adapter or a post-processor can add them. Tutilis's engine-call parser would be a post-processor that reads `ToolCall` entries and writes `tutilis.cogilis.engineCalls`.

### Raw events are the truth

Every line an agent emits is stored verbatim in `RawEvent(conversation, seq, receivedAt, line)`, compressed in blocks. Entries are a **parse** of that log. If a parser is wrong, or an agent's format changes, the entries can be rebuilt from raw events (`reparse`). The parser's version is stored on the conversation, so it is clear which records a parser fix would change.

## Storage

**One SQLite library file** holds all conversations, so search can run across them (L6). It runs in WAL mode, so the UI can read while an agent writes. On the JVM this is `org.xerial:sqlite-jdbc`, the driver Tutilis already ships, behind a small `Store` interface so a KMP driver can replace it later.

| Table | Holds | Note |
|---|---|---|
| `conversation` | id, title, binding, created, updated, outcome, cost, parser version, ext | One row per conversation. |
| `entry` | the skeleton: id, conversation, seq, parent, at, author, kind, state, inline text up to the threshold, blob ref, head, ext | Indexed on `(conversation, seq)` and `(parent)`. |
| `turn` | conversation, ordinal, branch, first and last entry seq, the user text head, the counts (commands, errors), duration, cost | Kept up to date on append, so a view can page by turn without scanning entries. |
| `branch` | id, conversation, name, head entry | |
| `raw_event` | conversation, seq range, compressed block | Lines in blocks of about 256 KB, zstd or gzip. |
| `blob` | sha256, size, mime, compressed bytes *or* a file path | Content-addressed and deduplicated. Bytes over 1 MB live as files beside the db. |
| `entry_fts` | FTS5 over user text, agent text, tool input, tool output | External content. Large outputs are indexed in full but stored once. |

**Paging (L3, L4).** A view asks for `turns(conversation, branch, range)` and gets turn skeletons: who said what, as heads, with counts. It asks for bodies (`entry(id)` and `blob(ref)`) only when something is on screen or opened. The client cache is a bounded LRU of bodies. Nothing loads a whole conversation, and the memory a conversation uses stays the same no matter how long it is.

**Append path (L7).** The adapter writes the raw line first, then upserts the entry. While text streams, a `Streaming` entry is updated in place, throttled to about 10 Hz on disk and kept live in memory. A crash loses at most the unflushed tail, and that tail can be rebuilt from the raw lines.

**Export.** A conversation exports to a portable folder: `conversation.json` (the skeleton and extensions), `blobs/`, and `raw.jsonl.gz`. That folder is what a git records repo, such as `plusauri/cogilis-conversations`, would hold. Import is the reverse. The folder, not the SQLite file, is the interchange format.

## Modules

| Module | Depends on | Holds |
|---|---|---|
| `kit-model` | kotlinx-serialization only | `Entry`, `Content`, `Conversation`, `Branch`, `Extensions`, and `SCHEMA`. Pure, `commonMain`. |
| `kit-store` | `kit-model`, sqlite | `Store` (append, page, search, blobs, raw events), migrations, and export/import. |
| `kit-agents` | `kit-model` | The `AgentAdapter` seam, `ClaudeLiveAdapter` (stream-json on stdin) and `GrokResumeAdapter`, each with a fixture test. |
| `kit-views` | `kit-model`, `kit-store`, Compose | `ConversationViewModel` (paging, cache, live updates), and the Messages, Tree and Search views. |
| `app` | all of the above | A window, a library picker, and settings. Kept thin. |

## Tests that prove the model

- **Fixture round trip:** each recorded transcript (Claude stream-json, Grok streaming-json) goes raw → entries → export → import → identical entries.
- **Cogilis parity:** a public Cogilis take (from a lesson plan) maps to entries with every step, output and timestamp accounted for. This is the 0001 proof.
- **Scale:** a synthetic conversation of 100k entries and 1 GB of output. Opening it, scrolling to the middle and searching it each stay within a fixed time and memory budget, asserted in the test.
- **Crash:** kill the writer mid-stream, reopen, and the entries rebuilt from raw events match.

## Open questions

1. **Thinking:** keep it, hidden by default (proposed), or drop it as Cogilis does?
2. **Branching in v1:** model it now (proposed: the cost is one parent column) but leave the UI for later?
3. **One library or several:** a single default library (proposed), with **File → Open Library** as in Tutilis?
4. **Blob threshold:** 4 KB inline, 1 MB to file (proposed). This needs tuning against real tool outputs.
5. **Storage driver:** `sqlite-jdbc` on the JVM now (proposed), or a KMP driver from the start so iOS stays possible?
