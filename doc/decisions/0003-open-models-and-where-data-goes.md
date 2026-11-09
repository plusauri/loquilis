# 0003: An easy path to open models, and showing where data goes

## Status

Direction and refinements accepted 2026-11-07 (Dan). Plain chat is built, as `LocalChatAgent`, `HttpChatSession` and `OpenAiChatParser`. On 2026-11-08 it ran against real Ollama 0.40.15 with `gemma4:e2b`: found with no setup, word by word, and Stop kept the partial reply. The indicator is built as `Agent.dataGoes` and `DataGoes`: under the message box in words, and as a mark in the conversation list. Claude reads as Anthropic unless `ANTHROPIC_BASE_URL`, Bedrock or Vertex redirects it (then unknown), Grok as xAI, and plain local chat as this computer when the runner's address is loopback and the model is not an Ollama cloud model, which plain chat never picks unasked. The rest is not built. Claims about other products are checked again before they are built on.

## The goal, as Dan put it

Using an agent console is straightforward for the big providers, who already offer their own. What is hard for someone new to AI is using the open offerings. Loquilis should solve that out of the box, so a person can get Loquilis, install a free and open option, and get questions answered without giving away their data.

Loquilis should also **show** when information is being sent to an agent in the cloud, and show when it is talking to a free agent that does not need cloud servers. For the most careful user, it could refuse questions until the internet is off.

Loquilis serves every kind of agent, with special consideration for people who want an easy path to open models.

## Refinements

- **A model runner is not an agent.** Ollama runs models. An agent is a separate program that adds tools on top of a model. A newcomer who only wants answers needs a runner and a model, and no agent at all. So there are two tiers: plain chat with a local model, which is the easy path, and a local agent with tools, which comes later.
- **Offer a short list, not a fixed "top three".** The best open models change every few months, and the right one depends on the machine's memory and disk. Loquilis checks the machine and recommends from a list it can update without a new release, in place of naming three forever.
- **Installing is the user's choice, and Loquilis carries them through it.** The goal is that a newcomer can use an open model with no terminal and no prior knowledge (Dan, 2026-11-07). So Loquilis notices when no runner is present, offers one clear path, and guides the install with plain steps, a link to the official installer, and a check that it worked. It downloads the recommended model for them, with its size stated and progress shown. It acts only with clear consent, and it never pipes a script from the internet into a shell. Models need the internet once, to download, and that is said plainly.
- **Say "open" carefully.** Most open models are open-weight, with licenses that vary. The product talks about where the data goes, which is what the person cares about.

## Terms

These four layers keep the product, its docs and its code clear:

| Layer | What it is | Cloud example | Local example |
|---|---|---|---|
| Model | The trained weights, which only turn text into text | Claude Opus, Grok | Qwen, Llama |
| Runner | What loads a model and serves it | The provider's servers | Ollama, LM Studio |
| Agent | The loop that gives a model tools and acts on what it says | Claude Code, the Grok CLI | OpenCode, Aider |
| Console | Where a person works with an agent or a model | Loquilis | Loquilis |

## Adding, changing and removing options

Keep what changes often in data and what is risky out of reach:

- **A catalog, as data.** Runners, models and agents, with official install links, the memory and disk each needs, and whether it stays local. It is a file Loquilis can refresh without a new release, a copy ships inside so it works offline, and a client console can replace it.
- **Generic adapters, so a new option needs no code.** One for the OpenAI-compatible HTTP interface that runners commonly serve, and one for the Agent Client Protocol that Grok speaks. `GrokAcpParser` would become the second, with Grok's extras on top. A new runner or agent that speaks either one is then a catalog entry. OpenCode documents ACP over stdin and stdout as `opencode acp`, so it is the likely first open agent. Others, including Goose and Aider, are still to be checked.
- **Memory is the first thing a newcomer feels.** On the first real run, a 16 GB Mac with other work open felt the system jitter while `gemma4:e2b` (7.5 GB on disk) loaded and answered. So the catalog recommends by memory free at the time, not only installed, says before a download what the model will take while it runs, and the console can tell the runner to unload the model when a conversation goes idle, since Ollama by default keeps it loaded for a few minutes.
- **A code seam for the rest.** `LibraryState` already takes a map of `Agent`s, so a client console can add its own.
- **No plugins that download and run code, for now.** A tool whose promise is that data stays private should not run third-party code it fetched.

## The indicator

Three states, in plain words, visible on every conversation and at the box you write in:

- **Sent to a company**, naming it: "Sent to Anthropic".
- **Stays on this computer.**
- **Unknown**, when Loquilis cannot tell. Never guess toward the safe answer.

What it can honestly rest on:

- **Loquilis cannot infer it from the name.** A local runner can also offer cloud-hosted models, and a local agent can use web tools or MCP servers. The state comes from the address actually in use, so `localhost` and a remote host differ, and from the tools turned on.
- **The answer is about the whole conversation, including tools.** A local model with a shell tool can still reach the network, because the model is not the only thing that can send data.
- **Words must match what is known.** "Configured to stay on this computer" is a claim Loquilis can stand behind. "Verified private" is not one, unless the network is actually off.

## Offline only

Dan's idea of refusing questions while the internet is on works, with limits, and it is the strongest promise on offer because a person can understand it.

- Check the machine's own network interfaces and routes. Do not probe a website to find out: that sends traffic to someone, in the one mode that promises none.
- It covers the machine, so a VPN or a phone hotspot coming up mid-conversation matters. Check before every send.
- Every cloud agent is unavailable in this mode, and Loquilis itself makes no network calls.

Stronger checks to look at later: watching which outside addresses the agent's process actually connects to, as evidence shown to the user, and running a local agent with no network permission at all, where the operating system allows it. Both vary by platform.

## How it fits the design

- `SessionEvent` and `AgentSession` do not require a command-line program, so a local runner's streaming HTTP reply can arrive as raw lines like any other. This is the first test of that seam, and it is worth doing early for that reason.
- Where data goes belongs on the conversation's `AgentBinding` in the model, so that it is recorded with the conversation.
- `Brand` and `ConsoleAction` set the pattern: a client console chooses what it offers, including offering only local agents.
