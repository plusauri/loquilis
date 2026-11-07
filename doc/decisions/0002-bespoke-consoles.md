# 0002: The library is for building bespoke consoles

## Status

Accepted, 2026-11-07. Direction only. None of this is built yet, and it does not need to be built before it is needed.

## Context

Loquilis is a library that people pull in to build their own agent consoles. The first one is a Cogilis console, themed with the Cogilis robot as its avatar. The Loquilis window is the plain console on top of the same library.

## What is coming

- The left conversations panel and the right inspector panel become collapsible.
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
