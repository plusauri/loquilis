# 0002: The library is for building bespoke consoles

## Status

Accepted, 2026-11-07. Built so far: the conversation list and the inspector are collapsible and resizable side panels (`PanelState`, `PanelSeam`), and the console keeps whether each shows and how wide it is (`ConsoleSettings`). Keyboard actions have begun (`ConsoleAction`, `KeyBindings`, `DefaultKeys`), and a client can pass its own `KeyBindings` to `LibraryWindow`. A client can pass its own `Palette` and type to the theme. Theming of the whole console, agent avatars and full keyboard use are still direction, to be built when they are needed.

## Context

Loquilis is a library that people pull in to build their own agent consoles. The first planned one is a console for Cogilis, Plusauri's agent workspace for Tutilis (see the README's "Terms"), themed with that agent's own avatar. The Loquilis window is the plain console on top of the same library.

## What is coming

- Clients theme the whole console.
- Clients give the agent an avatar image.
- The console is fully usable from the keyboard, and clients can change the shortcuts.

## How to build until then

- **Panels:** a view knows nothing of what sits beside it. The window lays panels out, so hiding one changes one place.
- **Theming:** colors come from `Palette`. Shapes, sizes and type belong in the theme as they come up, not as literals repeated across views. Nothing a client would want to change is decided deep inside a view.
- **Avatars:** the agent's look is a value the host passes in, never something a bubble decides. `Brand` already works this way for the console's own name and mark.
- **Keyboard:** actions get names first, and keys map to them through a table a client can replace. A view asks for an action, and does not test for a key. Anything a mouse can do is an action. This has begun: `ConsoleAction`, `KeyBindings` and `DefaultKeys` in `loquilis-views`, and `LibraryState.perform`.
- **Strings:** text a client might change is not buried in a composable, so it can move out when that is wanted.

New UI work follows these. When touching older code that does not, move it over if that is cheap, and otherwise leave it for the work that needs it.
