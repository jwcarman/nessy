# Nessy Console

One call from a `main` method to a working terminal agent.

```java
public static void main(String[] args) {
  Repl.run(config -> config
      .banner("nessy chat")
      .systemPrompt("You are a concise assistant living in someone's terminal.")
      .tool(new DaysUntilTool()));
}
```

That is a complete program. `Repl` assembles everything an engine needs so an
application does not have to:

- **The model** comes from `ModelDiscovery`, which reads whichever credentials
  are in the environment — set `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`,
  `XAI_API_KEY`, or `OPENAI_API_KEY` (with `OPENAI_BASE_URL` for a local
  runtime). Two providers with no `NESSY_PROVIDER` is refused rather than
  guessed. Put the adapters you want discoverable on your classpath; this
  module deliberately drags none of them in.
- **The actor system** forms a cluster of one and waits for it, because the
  engine always shards and sharding on a node that has not joined drops
  messages silently rather than failing.
- **State** is in memory — substrate, durable state, and reply tokens alike.

## What it is not

Nothing survives the process, on purpose. A conversation typed into a terminal
has no reason to outlive the terminal. An application that must survive a
restart is not a console application: assemble a `PekkoHarnessFactory` yourself,
or use `nessy-spring-boot-starter`.

There is no provider override. An application that wants to name its own
gateway is not reaching for an easy button, and has the ordinary way to say so.

## Configuration

Every setting has a working default, so a customizer that sets only a system
prompt is a complete program.

| | default |
|---|---|
| `banner(String)` | nothing printed |
| `prompt(String)` | `"> "` |
| `exitOn(String...)` | `exit`, `quit` (end of input always works) |
| `farewell(String)` | nothing printed |
| `systemPrompt(String)` | a generic assistant |
| `tool(Tool)` / `tool(Tool, binding)` | none |
| `agent(AgentType)` | `chat` |
| `id(AgentId)` | `cli` |
| `maxTokens(int)` | 4096 |

## The one thing worth reading the source for

`Harness#observe` is a post, not a call: it returns the moment the line is
durably the agent's problem, and the answer arrives later on other threads as
events. What makes a REPL out of that is the single place `ReplLoop` waits —
after posting a line it blocks until `TurnEnded`, so a person is never asked to
type over a reply still being written. An unattended application simply would
not wait.
