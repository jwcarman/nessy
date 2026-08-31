# Nessy Example: Chat CLI

The smallest complete Nessy application: a conversation in a terminal, with
one tool. No Spring, no database, no HTTP — four Java files, of which one is
the agent, one is the tool, and one is the assembly a real deployment would
never write by hand.

That last one is the point. `Runtime.java` builds the engine explicitly —
actor system, cluster of one, substrate, reply tokens — so you can see what
the Spring Boot starter is doing for the `chat-web` sibling, and that it is
nothing magic.

## What it shows

- **A harness with a tool.** `days_until` counts days to a date: something a
  model is bad at and a tool is trivially good at, so watching the model
  reach for it is watching tool use earn its keep.
- **Streaming.** Deltas print as they arrive.
- **The asynchrony under a synchronous-looking loop.** `harness.observe` is
  a post, not a call. The REPL blocks on `TurnEnded` because a *person* is
  waiting; an unattended application simply would not.

Nothing here survives the process. The durable-state store is Pekko's
in-memory one, which is the honest shape for a REPL: the conversation lives
exactly as long as the terminal it is typed into. Point `chat-web` at a
database to see the other half.

## Run it

Defaults target [LM Studio](https://lmstudio.ai) on `localhost:1234`, so a
local run costs nothing:

```bash
./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
```

Any OpenAI-compatible endpoint works — it is three environment variables:

```bash
OPENAI_BASE_URL=https://api.openai.com/v1 \
OPENAI_API_KEY=sk-… \
NESSY_MODEL=gpt-4o-mini \
  ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
```

`/quit` or Ctrl-D leaves.
