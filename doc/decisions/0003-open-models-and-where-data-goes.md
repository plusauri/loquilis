# 0003: An easy path to open models, and showing where data goes

## Status

Direction accepted 2026-11-07 (Dan). Nothing is built. The refinements below are proposals until Dan accepts them. Anything said here about other products comes from memory and is checked before it is built on.

## The goal, as Dan put it

Using an agent console is straightforward for the big providers, who already offer their own. What is hard for someone new to AI is using the open offerings. Loquilis should solve that out of the box, so a person can get Loquilis, install a free and open option, and get questions answered without giving away their data.

Loquilis should also **show** when information is being sent to an agent in the cloud, and show when it is talking to a free agent that does not need cloud servers. For the most careful user, it could refuse questions until the internet is off.

Loquilis serves every kind of agent, with special consideration for people who want an easy path to open models.

## Refinements

- **A model runner is not an agent.** Ollama runs models. An agent is a separate program that adds tools on top of a model. A newcomer who only wants answers needs a runner and a model, and no agent at all. So there are two tiers: plain chat with a local model, which is the easy path, and a local agent with tools, which comes later.
- **Offer a short list, not a fixed "top three".** The best open models change every few months, and the right one depends on the machine's memory and disk. Loquilis checks the machine and recommends from a list it can update without a new release, in place of naming three forever.
- **Installing is the user's act.** Loquilis points to each project's official installer, or runs it only with clear consent. It never pipes a script from the internet into a shell on its own. Models need the internet once, to download, and that is said plainly.
- **Say "open" carefully.** Most open models are open-weight, with licenses that vary. The product talks about where the data goes, which is what the person cares about.

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
