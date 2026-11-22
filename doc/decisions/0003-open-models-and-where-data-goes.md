# 0003: An easy path to open models, and showing where data goes

## Status

Accepted. Claims about other products are checked again before they are built on.

### Built so far

- **Plain chat with a local model:** `LocalChatAgent` (in `loquilis-store`), with `HttpChatSession` and `OpenAiChatParser` (in `loquilis-agents`) speaking a runner's OpenAI-compatible HTTP interface. Replies stream word by word, and Stop keeps the partial reply.
- **The indicator:** `Agent.dataGoes` and `DataGoes`, shown in words under the message box and as a mark in the conversation list. Claude reads as Anthropic unless `ANTHROPIC_BASE_URL`, Bedrock or Vertex redirects it, which reads as unknown. Grok reads as xAI. Plain local chat reads as this computer when the runner's address is loopback and the model is not an Ollama cloud model, which plain chat never picks unasked.
- **Guided setup:** `OllamaSetup` and `SetupPanel`. A new local conversation checks for Ollama and a model, sends a newcomer to Ollama's own download page, starts Ollama, and downloads a model from the shipped catalog (`local-models.json`) with progress. It recommends the largest model that uses at most about a third of installed memory (free memory swings too much to rest on), and checks the disk first.
- **Offline only:** `OfflineOnly` and `joinedNetworks`. A switch, kept between runs, refuses a send while any network interface holds an address beyond loopback and link-local, or while the conversation goes anywhere but this computer. It is checked before every send. Downloads and the Ollama site are off while it is on.
- **Model memory:** a local model is held in memory for a few minutes after it answers (two by default, set in `ConsoleSettings`) and let go when the console quits.
- **OpenCode, the first open agent:** `OpenCodeAgent` and `OpenCodeLiveSession`, read by `AcpParser`, the same parser Grok uses. As OpenCode comes, it runs every tool without asking, starts on its own cloud model, and reads the person's Claude instructions. Loquilis starts it with permissions set to ask (reading and searching allowed), with its free cloud models and its reading of Claude instructions turned off, sets the model over ACP, and sends nothing until OpenCode confirms the model. Only models Ollama says can call tools are offered.

Not built yet: a catalog refreshed without a new release, other runners and agents, and the stronger network checks under "Offline only" below.

## The goal

Using an agent console is straightforward for the big providers, who already offer their own. What is hard for someone new to AI is using the open offerings. Loquilis should solve that out of the box, so a person can get Loquilis, install a free and open option, and get questions answered without giving away their data.

Loquilis should also **show** when information is being sent to an agent in the cloud, and show when it is talking to a free agent that does not need cloud servers. For the most careful user, it could refuse questions until the internet is off.

Loquilis serves every kind of agent, with special consideration for people who want an easy path to open models.

## Refinements

- **A model runner is not an agent.** Ollama runs models. An agent is a separate program that adds tools on top of a model. A newcomer who only wants answers needs a runner and a model, and no agent at all. So there are two tiers: plain chat with a local model, which is the easy path, and a local agent with tools.
- **Offer a short list, not a fixed "top three".** The best open models change every few months, and the right one depends on the machine's memory and disk. Loquilis checks the machine and recommends from a list kept as data, in place of naming three forever.
- **Installing is the user's choice, and Loquilis carries them through it.** The goal is that a newcomer can use an open model with no terminal and no prior knowledge. So Loquilis notices when no runner is present, offers one clear path, and guides the install with plain steps, a link to the official installer, and a check that it worked. It downloads the recommended model for them, with its size stated and progress shown. It acts only with clear consent, and it never pipes a script from the internet into a shell. Models need the internet once, to download, and that is said plainly.
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

- **A catalog, as data.** Runners, models and agents, with official install links, the memory and disk each needs, and whether it stays local. Today it is the models in `local-models.json`, shipped inside so it works offline. Refreshing it without a new release, and letting a client console replace it, are still to come.
- **Generic adapters, so a new option needs little code.** One for the OpenAI-compatible HTTP interface that runners commonly serve (`OpenAiChatParser`, `HttpChatSession`), and one for the Agent Client Protocol (`AcpParser`), which serves both Grok and OpenCode. A new runner or agent that speaks either one then needs only a small `Agent`. Others, including Goose and Aider, are still to be checked.
- **Memory is the first thing a newcomer feels.** On the first real run, a 16 GB Mac with other work open felt the system jitter while a 7.5 GB model loaded and answered. So the recommendation leaves most of the installed memory to everything else, the catalog says before a download what the model will take while it runs, and the console tells the runner to let the model go after a short idle time and on quit, since Ollama by default keeps it loaded for a few minutes.
- **A code seam for the rest.** `LibraryState` takes a map of `Agent`s, so a client console can add its own.
- **No plugins that download and run code, for now.** A tool whose promise is that data stays private should not run third-party code it fetched.

## The indicator

Four states, in plain words, visible on every conversation and at the box you write in (`DataGoes`):

- **Sent to a company** (`ToCompany`), naming it: "Sent to Anthropic".
- **Stays on this computer** (`StaysHere`): a runner on this computer, with no tools that could reach further.
- **Model on this computer, with tools that can reach the network** (`ModelHere`): a local agent such as OpenCode, whose shell or web tools can send data even though the model runs here.
- **Unknown** (`Unknown`), when Loquilis cannot tell. Never guess toward the safe answer.

What it can honestly rest on:

- **Loquilis cannot infer it from the name.** A local runner can also offer cloud-hosted models, and a local agent can use web tools or MCP servers. The state comes from the address actually in use, so `localhost` and a remote host differ, and from the tools turned on.
- **The answer is about the whole conversation, including tools.** A local model with a shell tool can still reach the network, because the model is not the only thing that can send data.
- **Words must match what is known.** "Configured to stay on this computer" is a claim Loquilis can stand behind. "Verified private" is not one, unless the network is actually off.

## Offline only

Refusing questions while the internet is on works, with limits, and it is the strongest promise on offer because a person can understand it.

- Check the machine's own network interfaces. Do not probe a website to find out: that sends traffic to someone, in the one mode that promises none.
- It covers the machine, so a VPN or a phone hotspot coming up mid-conversation matters. Check before every send.
- Every cloud agent is unavailable in this mode, and Loquilis itself makes no network calls.

Stronger checks to look at later: watching which outside addresses the agent's process actually connects to, as evidence shown to the user, and running a local agent with no network permission at all, where the operating system allows it. Both vary by platform.

## How it fits the design

- `SessionEvent` and `AgentSession` do not require a command-line program, so a local runner's streaming HTTP reply arrives as raw lines like any other. `HttpChatSession` proved that seam.
- Where data goes is worked out today by `Agent.dataGoes` from the conversation and the agent's settings. Recording it on the conversation's `AgentBinding` in the model, so that it stays with the conversation, is still to come.
- `Brand` and `ConsoleAction` set the pattern: a client console chooses what it offers, including offering only local agents.
