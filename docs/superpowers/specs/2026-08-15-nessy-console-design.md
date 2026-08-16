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

## 4a. `nessy-model-env` — the provider follows the key (amended in
session, owner: "switching to openai would be simply including that
env var")

A micro-module depending on BOTH provider modules non-optionally (its
whole point: both on the classpath so either key just works). One
method — `EnvModelProviders.fromEnv()` (naming at implementer taste in
the family voice): `ANTHROPIC_API_KEY` present → Anthropic;
`OPENAI_API_KEY` present → OpenAI; both → `NESSY_PROVIDER`
(`anthropic`/`openai`) breaks the tie, defaulting Anthropic with a
one-line notice; neither → fail-noisy naming exactly the variables it
checked. No reflection, no Spring, ~twenty lines plus javadoc. Offline
tests via an env-reading seam (a `Map<String,String>` parameter with
the public method reading the real environment — the storeSet-style
honest minimum).

## 5. The three mains collapse — and become two

- `chat-cli` **consolidates to ONE main** (`Chat`): the env helper is
  the provider lesson now ("switch providers by switching the key"),
  strictly better teaching than two parallel mains. The `events()`
  subscription SURVIVES in the one main; the old two-main contrast
  moves into README prose. `OpenAiChat`'s exhibit duty passes to the
  helper (the OpenAI module rides chat-cli's classpath via
  `nessy-model-env`). Hand-rolled render loops and the module-private
  `ConsoleApprover` go.
- `Scout`: the toolbox block + grants (its lesson, untouched — the
  construction seam and grant table survive verbatim) +
  `ConsoleRepl...run()`, provider via `fromEnv()`. **`ScoutTest` must
  pass UNTOUCHED** — it exercises the grant table through the seam,
  not the REPL loop or the provider choice.
- Boot examples are already property-driven via the starter — a README
  parity sentence, no code.
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

## 9. Amendment (owner-ruled, 2026-08-15): the plan checklist

The console learns to show the plan the model keeps — Claude Code's todo
rendering, translated to the SGR-only covenant (§"hard line" unchanged: no
raw mode, no cursor addressing; transcript-flow rendering only).

- **`ConsoleRepl.Builder.plan(PlanStore store)`** — opt-in, mirroring the
  grant principle: the console never guesses a plan facility exists; the
  app that granted `update_plan` hands the same store to the repl. At most
  once; null rejected.
- **Render on change, end of turn:** the repl remembers the last plan it
  printed; after each completed tell it reads `store.find(id)` for its own
  conversation and prints the checklist only when the plan is present,
  non-empty, and different from the last one printed. Quiet turns stay
  quiet; three `update_plan` calls in one turn print once. Clearing or
  finishing a plan simply stops the printing (the final all-DONE state
  renders because it differs; an absent/empty read after that renders
  nothing).
- **`ConsoleRenderer` grows the checklist style:** two-space indent, one
  line per task — `DONE` dim + strikethrough (SGR 9 joins the `Ansi`
  helper), `IN_PROGRESS` bold with the `◐` marker, `PENDING` plain `☐`;
  markers fall back to `[x]`/`[>]`/`[ ]` when styling is disabled
  (non-TTY, NO_COLOR, TERM=dumb — the existing detection, untouched).
- **No new dependencies:** `PlanStore`/`Plan` live in nessy-core's
  `spi.plan`, which nessy-console already sees.

Consumers: scout wires it as its showcase (multi-step research with a
visible plan — see the scout design's own amendment); chat-cli adds the
one-liner since it already holds the store.

## 10. Amendment (2026-08-15): the farewell line

`ConsoleRepl.Builder.farewell(String)` — optional, at most once, null
rejected — names a line `run()` prints (dim-styled per §2's covenant, plain
when styling is disabled) the instant the loop ends, exit word or EOF alike,
before `run()` returns; unset, the loop ends exactly as before. Scout also
calls `System.exit(0)` right after its try-with-resources block closes
`McpToolbox`: the MCP transport's `HttpClient` carries a non-daemon selector
thread that outlives `close()`, so without the explicit exit the process
lingers seconds after a clean run — the farewell prints instantly either way,
this only shortens what came after.
