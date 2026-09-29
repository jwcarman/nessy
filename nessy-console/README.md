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

- **The provider** comes from a minimal Spring Boot context this call raises
  and tears down around itself: `nessy-spring-boot-autoconfigure` contributes
  an `InferenceProvider` bean, named for its vendor, once that vendor's API
  key is in the environment. Set `ANTHROPIC_API_KEY`, `XAI_API_KEY`, or
  `OPENAI_API_KEY` (with `OPENAI_BASE_URL` for a local runtime) to light one,
  then say which one answers with `NESSY_PROVIDER` and which model with
  `NESSY_MODEL` — the two are a pair, so set both or neither. With none set,
  the console names every provider it found. Put the adapter jar you want on
  your classpath; this module deliberately drags none of them in.
- **The conversation itself stays in memory.** The terminal is the
  conversation: a turn that has ended has ended, and a CLI that resumed
  yesterday's chat would surprise the person typing into it. A `DataSource`
  is still worth having when a tool brings its own store — a notebook, a
  plan — and `dataSource(...)` on the config, or `SPRING_DATASOURCE_URL`
  from the same Boot context, is where that comes from. Its schema is
  applied on the way in.

## What it is not

There is no provider override. An application that wants to name its own
adapter is not reaching for an easy button, and has the ordinary way to say
so: `DefaultDirectHarnessFactory` directly, or `nessy-spring-boot-starter`.

## Configuration

Every setting has a working default, so a customizer that sets only a system
prompt is a complete program.

| | default |
|---|---|
| `banner(String)` | nothing printed |
| `prompt(String)` | `"> "` |
| `exitOn(String...)` | `exit`, `quit`, `/exit`, `/quit`, any case (end of input always works) |
| `farewell(String)` | nothing printed |
| `systemPrompt(String)` / `systemPrompt(SystemPromptSource)` | a generic assistant |
| `tool(Tool)` / `tool(Tool, binding)` | none |
| `agent(AgentType)` | `chat` |
| `id(AgentId)` | one fixed id for the terminal, rather than a random one each run |
| `maxTokens(int)` | 4096 |
| `dataSource(DataSource)` | the Boot context's |
| `harness(customizer)` | reaches the full `DirectHarnessConfig<String>` |

## The one thing worth reading the source for

`DirectHarness#ask` is a call, not a post: it runs the whole turn on the
calling thread and returns the `Outcome` when it is over, so `ReplLoop` has
nothing to wait for once it has called it. What makes streaming work
without printing an answer twice is the listener: a provider that streams
writes each delta to the terminal as it arrives, and `reportAnswer` prints
the value `ask` returned only when nothing was already written that way.
