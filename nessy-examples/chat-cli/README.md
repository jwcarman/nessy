# Nessy Example: Chat CLI

The second example, and the interactive terminal front door: a plain `while`
loop reading lines from the console and driving one long-lived
`Conversation<String>`, no Spring, no HTTP, one agent definition
(`DemoAgent`) run against either provider. The lesson is live narration —
`AnthropicChat` and `OpenAiChat` each hand a `TurnObserver` straight to
`Conversation#tell`, and that observer prints `TextDelta`s as they arrive, so
the model's prose appears on screen the same way it appears in a real chat
client: word by word, not as one block after the turn settles. That's why
neither main adopts `TurnObserver.logging(Logger, prefix)` — `logging` only
narrates settled facts (`AssistantSaid`, tool requested/completed/parked, the
ending), never deltas, and a streaming REPL's whole point is the deltas.
`AnthropicChat` additionally subscribes to `Conversation#events()` for
`ToolFinished`, printing it as a second, independent line — the fact-log side
of the same story, alongside whatever the turn observer renders live.

## Run it

```bash
ANTHROPIC_API_KEY=… ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java -Dexec.mainClass=org.jwcarman.nessy.examples.AnthropicChat
```

```bash
OPENAI_API_KEY=… ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java -Dexec.mainClass=org.jwcarman.nessy.examples.OpenAiChat
```

The `-am` flag also builds this module's reactor dependencies (`nessy-core`
and whichever of `nessy-model-anthropic` / `nessy-model-openai` the chosen
main needs) — the first run compiles that whole upstream chain and takes
noticeably longer; every run after is fast, since Maven only recompiles what
changed.

No Docker, no database: the conversation is JVM-lifetime state, same as
`night-watchman` — nothing here asks for durability, and there's nothing to
stand up beyond the JVM itself.

## `java.lang.IO`

The mains print and read with bare `IO.println(...)` / `IO.readln(...)`
calls — no `System.out`, no import. That's `java.lang.IO`, the console I/O
class JEP 512 (Compact Source Files and Instance Main Methods, finalized in
JDK 25) makes implicitly available everywhere `java.lang.*` already is: a
small, blocking, `String`-in/`String`-out surface meant for exactly this kind
of terminal example, not a `Scanner`/`BufferedReader` ceremony. `IO.readln`
returns `null` at EOF (Ctrl-D at the console) rather than throwing; both mains
treat that the same as `/quit`.

## The approval prompt

`ClockTool` is granted `UsagePolicy.requireApproval()` (on purpose — every
demo run exercises the gate), so asking the model for the time parks the turn
on `ConsoleApprover`, which prints the request and blocks on an answer:

```
you> what time is it?
⚙ tool: clock
approve: read the current time
y/n>
```

Anything other than `y` (including EOF) reads as a denial
(`"declined at the console"`); the conversation then continues from wherever
the model takes the denial, same as any other turn.
