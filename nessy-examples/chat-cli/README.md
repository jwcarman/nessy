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

Which model it talks to is not written down here. `ModelDiscovery` reads the
environment and picks whichever provider has credentials, so the same command
runs against any of them — and says which one it chose in the banner.

Against [LM Studio](https://lmstudio.ai) or any other OpenAI-compatible local
runtime, which costs nothing:

```bash
OPENAI_API_KEY=not-needed \
OPENAI_BASE_URL=http://localhost:1234/v1 \
NESSY_MODEL=qwen/qwen3.6-35b-a3b \
  ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
```

Against a real vendor, it is one variable:

```bash
ANTHROPIC_API_KEY=… ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
GEMINI_API_KEY=…    ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
XAI_API_KEY=…       ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
OPENAI_API_KEY=…    ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
```

`NESSY_MODEL` names a specific model instead of the winning provider's default.
Set two providers' keys and discovery refuses to guess — it names both and asks
for `NESSY_PROVIDER`, because a coin toss over which vendor gets billed is not
a default anyone wants. Set none and it lists every variable it looked at.

Bedrock is deliberately not discoverable: ambient AWS credentials mean someone
once deployed something to AWS, not that they chose Bedrock for this. An
application that wants it constructs it explicitly.

`/quit` or Ctrl-D leaves.
