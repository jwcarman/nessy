# nessy-console — the terminal front door becomes a library

**Date:** 2026-08-15
**Status:** APPROVED — 2026-08-15 (owner rulings in session: "our next
bet"; name `nessy-console` chosen over nessy-cli/nessy-repl; "SGR-only"
styling ambition — "make something that looks really nice like claude
code"; zero dependencies, Spring Shell rejected by shape, JLine named
the v2 trigger; fold ruling: built on the scout branch, paperwork once)

---

## 1. The evidence and the goal

Three mains hand-roll the same loop — chat-cli's `AnthropicChat` and
`OpenAiChat`, and scout's `Scout` — read a line, tell the agent, render
deltas, prompt again; `ConsoleApprover` exists as two byte-identical
copies. That is the thrice-paid tax. The extraction gives every example
back its one lesson, gives applications "console-chat with my agent in
one line," and gives the family a shared look.

New module **`nessy-console`**: depends on `nessy-core` ONLY. No JLine,
no Jansi, no picocli, no Spring — SGR styling is ~thirty lines of
constants and the loop is `java.lang.IO`. **The v1 hard line: styling
yes, terminal takeover no** — SGR (`ESC[…m`) plus the `\r` spinner; no
raw mode, no cursor addressing, no alternate screen. JLine is the named
v2 trigger (history/completion, taken deliberately if ever). Spring
Shell rejected by shape (command shells, not chat REPLs); a future
Boot-console example combining the starter with this library is banked,
not built.

## 2. `Ansi` — the style helper

A tiny final class: `bold`, `dim`, `italic`, and a small palette
(`cyan`, `yellow`, `red`, `green` — what the default look needs, not a
color system), each a `String style(String text)`-shaped method, plus
`enabled()`. Styling auto-disables when the output is not a TTY
(`System.console() == null`), when `NO_COLOR` is set (any value), or
when `TERM=dumb` — one check, cached, with a package-private override
seam for tests. Disabled means the text passes through untouched —
piping to a file yields clean text.

## 3. `ConsoleRepl` — the loop

```java
ConsoleRepl.of(agent)
    .banner("scout — ask about any public GitHub repo")
    .prompt("you> ")            // default "> "
    .exitOn("exit", "quit")     // the defaults
    .run();
```

- `of(Agent<String>)` drives `agent.converse()` — one conversation per
  run, the shape all three mains have today.
- `run()`: print banner (styled), loop — prompt, read line
  (`java.lang.IO`), exit words end it, otherwise `tell` with the
  default renderer observing. Blank lines re-prompt.
- **The default renderer** (a `TurnObserver`, built on `builder()`):
  streaming text plain; thinking deltas dim-italic; `⚙ tool:` lines dim
  (requested/completed/parked, park token shown); `TurnEnded` FAILED →
  red line with the reason; a `\r` **spinner** between send and the
  first delta, erased by the first token (a virtual thread; stops on
  first event; never runs when styling is disabled — a dumb pipe gets
  no spinner frames).
- `.renderer(TurnObserver)` overrides the default wholesale; the
  default is also exposed (`ConsoleRenderer.observer()` or similar) so
  callers can compose.
- Testability seam: a package-private constructor takes the
  reader/writer pair; the public path uses the real console. The loop,
  renderer, spinner, and approver are all unit-tested through injected
  streams — no console needed in the default build.

## 4. `ConsoleApprover` comes home

The class moves from example-land into `nessy-console` (public, tested
— it is library code now): the approval prompt renders HIGHLIGHTED (the
security beat should pop — bold/yellow), showing the tool's
`describe(...)` line; y/n via the same injected-stream seam. chat-cli's
and scout's copies DELETE.

## 5. The three mains collapse

- `AnthropicChat` and `OpenAiChat`: provider construction + their
  distinct lessons (the two providers; the `events()` contrast, which
  stays) + `ConsoleRepl.of(agent)...run()`. Hand-rolled render loops and
  the module-private `ConsoleApprover` go.
- `Scout`: the toolbox block + grants (its lesson, untouched — the
  construction seam and grant table survive verbatim) +
  `ConsoleRepl...run()`. **`ScoutTest` must pass UNTOUCHED** — it
  exercises the grant table through the seam, not the REPL loop.
- Behavior parity, not byte parity: the styled default may render
  *nicer* than the old hand-rolls; what must survive is the information
  (deltas, tool lines, endings, approval prompts).

## 6. Paperwork — once

`nessy-console/README.md` (the one-liner promise, the look, the
SGR-only covenant, NO_COLOR citizenship, the JLine v2 note); scout's
README refreshed post-collapse (already written in Task 1 of the scout
plan — updated where the REPL mechanics changed, the grants lesson
untouched); chat-cli README refreshed (its mains got thinner; its
lessons unchanged); root README (Install row + the examples intro
noting the shared console library); CHANGELOG `### Added` covering
nessy-console AND scout together (they merge together). Full offline +
container sweeps.

## 7. Not in this wave

JLine/history/completion (v2 trigger); Windows-legacy (Jansi) —
modern-terminal assumption documented; the Boot-console example;
markdown rendering of assistant prose (tempting, unbounded — banked);
any TurnEvent/core change (the renderer composes on the existing
builder, or it does not ship).

## 8. Breaking (pre-1.0)

None to published API. chat-cli's package-private `ConsoleApprover`
and scout's copy die unpublished; the mains' behavior upgrades
in-place.
