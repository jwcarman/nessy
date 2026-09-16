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
  an `InferenceProvider` bean once a vendor's API key is in the environment.
  Set `ANTHROPIC_API_KEY`, `XAI_API_KEY`, or `OPENAI_API_KEY` (with
  `OPENAI_BASE_URL` for a local runtime), and the model id comes from
  `NESSY_MODEL`. Put the adapter jar you want on your classpath; this module
  deliberately drags none of them in.
- **The database** is the `DataSource` that same context has, so
  `SPRING_DATASOURCE_URL` (with `USERNAME` and `PASSWORD`) is the easy button,
  and `dataSource(...)` on the config replaces it. The schema is applied on
  the way in. A conversation therefore survives the process: the agent id is
  fixed per terminal, so the next run picks up the same story.

## What it is not

There is no provider override. An application that wants to name its own
adapter is not reaching for an easy button, and has the ordinary way to say
so: `DefaultHarnessFactory` directly, or `nessy-spring-boot-starter`.

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
| `id(AgentId)` | one fixed id for the terminal, so a returning person finds the same conversation |
| `maxTokens(int)` | 4096 |
| `dataSource(DataSource)` | the Boot context's |
| `harness(customizer)` | reaches the full `HarnessConfig` |

## The one thing worth reading the source for

`Harness#observe` is a post, not a call: it returns the moment the line is
durably the agent's problem, and the answer arrives later on other threads as
events. What makes a REPL out of that is the single place `ReplLoop` waits —
after posting a line it blocks until `TurnEnded`, so a person is never asked to
type over a reply still being written. An unattended application simply would
not wait.
