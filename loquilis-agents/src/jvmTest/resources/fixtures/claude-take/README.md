# Fixture: a recorded Claude Code session

**License:** the files in this folder are © Plusauri, LLC and licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). They are not covered by the repo's MIT license.

This is a *take*: one recorded session of Cogilis, Plusauri's agent workspace for its retirement-planning app Tutilis (see the repo's README, "Terms"). It was copied from Plusauri's records of Cogilis sessions (not public), take `claim_early_invest_or_wait/claim_invest_stocks/2026-11-01-2041-claude-cli`. The household in it is a fictional lesson plan, and the recorder had already scrubbed paths and session ids.

Before it was added here, the recording was scrubbed further:

- The recording account's settings were removed from the stream: connected services and their tools, plugins, skills and slash commands, memory paths and the socket path.
- The app's build details (version, commit and branch) were replaced with placeholders.

The files:

- `raw.jsonl.gz`: the agent's stream, as it arrived, apart from the scrubbing above.
- `conversation.json`: what the recorder made of the same stream. `ClaudeParityTest` reads it as plain JSON, as a statement of what must be found in the stream. Loquilis links to no Tutilis code.
