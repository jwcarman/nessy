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
  and tears down around itself: `nessy-spring-boot-autoconfigure` registers
  an `InferenceProvider` bean per preset it can light, named by the preset's
  id. Set `ANTHROPIC_API_KEY`, `XAI_API_KEY`, `OPENAI_API_KEY` (with
  `OPENAI_BASE_URL` for a local runtime), or `GEMINI_API_KEY` to light one;
  `NESSY_PROVIDERS_LMSTUDIO_ENABLED=true` lights the keyless `lmstudio`
  preset. Then say which one answers with `NESSY_PROVIDER` and which model
  with `NESSY_MODEL` — the two are a pair, so set both or neither. With none
  set, the console names every provider it found. Put the adapter jar you
  want on your classpath; this module deliberately drags none of them in.
- **The conversation itself stays in memory.** On the console's own path the
  conversation ends with the process, and the console says so; an
  application with a durable backend hands over its own factory and can
  resume a conversation by id. A `DataSource`
  is still worth having when a tool brings its own store — a notebook, a
  plan — and `dataSource(...)` on the config, or `SPRING_DATASOURCE_URL`
  from the same Boot context, is where that comes from. Its schema is
  applied on the way in.

## Conversations

Every launch starts a new conversation under a freshly minted id, and the
console says so:

```
conversation 0198…: resume with --nessy.console.agent=0198…
```

Type `/clear` to start another one for the rest of the session; it prints
the new id the same way. The old conversation is left as it is, not
terminated, so its id still resumes it. `/config` shows the id in use.

To resume, name the id: `id(AgentId)` on the config, or, when the console
raises its own Boot context, the property `nessy.console.agent`
(`--nessy.console.agent=<id>` or `NESSY_CONSOLE_AGENT=<id>`). Whether a
resumed conversation still has its history depends on where it is kept: the
in-memory default ends with the process; a JDBC-backed one survives.

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
| `id(AgentId)` | a freshly minted UUIDv7 each launch; pass one to resume that conversation |
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
