# Nessy Example: Chat CLI

The smallest complete Nessy application: a conversation in a terminal, with
one tool. No Spring, no database, no HTTP — two Java files, one of which is
the tool.

It used to be four, and one of those was eighty lines of engine assembly. That
moved into `nessy-console`, where it is library code rather than an example of
what an application should not have to write.

## What it shows

`Chat.java` is 53 lines, and this is all of it:

```java
Repl.run(config -> config
    .banner("nessy chat — Ctrl-D or /quit to leave")
    .systemPrompt(SYSTEM_PROMPT)
    .tool(new DaysUntilTool()));
```

`nessy-console` owns everything else — discovering the model, forming the actor
system's cluster of one, the in-memory substrate and reply tokens, and the loop
that streams an answer as it arrives. What is left here is the only part that is
about THIS program: what it is for, and what it can do.

- **Tools worth having.** `today` and `days_until` cover what a model cannot
  know and cannot compute: it has a training cutoff and a confident prior about
  what year it is, so asked about Christmas shopping it will name the wrong year
  and reason from it. The date is in the system prompt too — a tool only helps
  if the model thinks to call it.
- **A tool a person has to allow.** `send_email` is gated by
  `ConsoleApprover`, which asks right there at the prompt:

  ```
    ⚠ Send an email to jim@example.com, subject "Dinner"
      allow? [y/N]
  ```

  It answers on the spot rather than parking the question, because the person
  is at the keyboard with the agent's output still on screen. Anything but
  `y`/`yes` is a no, and end of input is a no — silence is not consent.
- **Streaming.** The answer is typed out as the model writes it.

Nothing survives the process — state lives exactly as long as the terminal it is
typed into. Point `chat-web` at a database to see the other half.

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
