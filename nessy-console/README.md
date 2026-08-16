# Nessy Console

The terminal front door, once. Three examples hand-rolled the same loop —
read a line, tell the agent, render deltas, prompt again — byte-identical
`ConsoleApprover` included. This module is that lesson extracted: depend on
`nessy-core` alone, and console-chat with any `Agent<String>` is one line.

```java
ConsoleRepl.run(
    agent,
    r ->
        r.banner("scout — ask about any public GitHub repo")
            .prompt("you> ")            // default "> "
            .exitOn("exit", "quit"));   // the defaults
```

`run(Agent<String>, ReplCustomizer)` opens one conversation and drives it for
the life of the loop — the exact shape every hand-rolled REPL in this family
shared before this module existed. The loop prints the banner (if any), then
loops: print the prompt, read a line, an exit word ends the loop, a blank
line reprompts without telling the agent, anything else is told with the
default renderer watching. `.renderer(TurnObserver)` overrides that default
wholesale; the
default itself is exposed as `ConsoleRenderer.observer(Writer)` so a caller
can fold its behavior into a composed observer of their own rather than
choosing between "the whole look" and "none of it."

## The look

The default renderer streams assistant prose plain — no styling at all, the
same way a real chat client shows it word by word:

```
hello
```

Thinking deltas render dim-italic. Tool activity gets one dim `⚙ tool:` line
per requested/completed/parked event, framed by blank lines, the parked line
carrying the park token:

```

⚙ tool: clock requested

⚙ tool: clock completed
```

```

⚙ tool: clock parked (wait-1)
```

A `FAILED` turn ending renders one red line with its reason; every other
ending (`COMPLETE`, `IDLE`, `PARKED`) renders nothing here — `ConsoleRepl`
already leaves a blank line after every told turn:

```

! too many tool errors
```

Between sending and the first token, a `\r`-based spinner fills the wait on
its own virtual thread — one carriage return and an overwrite, never a raw
addressing sequence — and is erased the instant the first `TurnEvent`
arrives, whichever kind it is. A turn that throws before any event narrates
(a provider or network failure) still stops the spinner and renders one red
line, from `ConsoleRepl`'s own `finally` — a single bad turn costs a red line,
not the rest of the session.

The approval gate, `ConsoleApprover`, prints its prompt HIGHLIGHTED —
bold-yellow, the one place in the whole default look that reaches for two
styles at once, because the security beat should pop:

```

approve: read the current time
y/n>
```

Garbage input reprompts (`please answer y or n`) rather than reading as a
denial; only `y`/`n`, case-insensitive and whitespace-trimmed, settle the
question. End of input (an unattended pipe, a closed terminal) reads as a
denial with reason `"declined at the console"` — the same conservative
default garbage input used to fall back to before the reprompt existed.

## The SGR-only covenant

**Styling yes, terminal takeover no.** `Ansi` wraps text in SGR codes
(`ESC[…m`) — `bold`, `dim`, `italic`, and a four-color palette (`cyan`,
`yellow`, `red`, `green`) sized to what the default look actually needs, not
a color system — plus the spinner's own `\r` overwrite. Nothing here does
cursor addressing, raw mode, or an alternate screen; that line is deliberate,
not an oversight (see "What's next" below).

`Ansi.enabled()` is computed once, from three checks any well-behaved
terminal program makes, and cached:

- Is a real console attached (`System.console() != null`)?
- Has the caller opted out (`NO_COLOR` set to any value at all — the
  [NO_COLOR convention](https://no-color.org))?
- Is the terminal one that cannot render SGR at all (`TERM=dumb`)?

When styling is disabled, every `Ansi` wrapper is an exact pass-through —
piping this library's output to a file or another process yields clean,
colorless text, byte for byte — and the spinner writes **zero bytes**, not
merely an invisible frame: a piped consumer never sees a stray `\r`.

## The renderer as a `TurnObserver`

`ConsoleRenderer.observer(Writer)` is built on `TurnObserver.observe(TurnObserverCustomizer)` —
this module's own dogfood of the same composition point `night-watchman`'s
`Watchman` and `order-desk`'s `OrderDesk` dogfooded before it. Reach for it
directly (rather than `ConsoleRepl`'s default) when an application wants the
console look composed alongside another concern — a transcript file, a
metrics counter — on the same observer.

## Render-and-continue

A turn that fails renders and moves on: `ConsoleRepl` catches whatever a
`Conversation#tell` call throws, stops the spinner, prints one red line with
the failure's reason, and reprompts — the REPL never crashes on a flaky
network call or a broken renderer. `ConsoleRenderer` gives the same shape to
a narrated `TurnEnded(FAILED, reason)` fact, so the two paths (an exception
that never reached the renderer, and a settled failed ending that did) read
identically at the console.

## What's next

JLine is the named v2 trigger — history, line editing, tab completion — taken
deliberately if this module ever needs a real terminal library, not
backed into v1 by accident. Spring Shell was considered and rejected by
shape: it builds command shells, not chat REPLs. Windows-legacy terminals
(the kind that need Jansi to render SGR at all) are out of scope — this
module assumes a modern terminal and says so here rather than silently.
