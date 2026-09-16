# Nessy Example: Chat CLI

The smallest complete Nessy application: a conversation in a terminal, with
one tool. No database, no HTTP, no Spring code of your own to write — two
Java files, one of which is the tool.

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

`nessy-console` owns everything else — raising the Boot context that finds the
model, the in-memory substrate and reply tokens, and the loop that streams an
answer as it arrives. What is left here is the only part that is about THIS
program: what it is for, and what it can do.

- **A tool worth having.** `days_until` counts days to a date: something a model
  is bad at and a tool is trivially good at.
- **A fact a tool could not fix.** The date is in the system prompt. There was a
  `today` tool here first and it did not help: asked about Christmas shopping,
  the model named the wrong year and reasoned from it *without calling anything
  to check*. A tool only works if the model volunteers to use it, and that is
  precisely the failure where it does not.
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

Which model it talks to is not written down here. Each provider module on
this example's classpath ships its own Boot `@AutoConfiguration`, so setting
that vendor's key is the whole choice — the same command runs against any of
them.

Against [LM Studio](https://lmstudio.ai) or any other OpenAI-compatible local
runtime, which costs nothing:

```bash
OPENAI_API_KEY=not-needed \
OPENAI_BASE_URL=http://localhost:1234/v1 \
NESSY_MODEL=qwen/qwen3.6-35b-a3b \
  ./mvnw -q -pl :nessy-example-chat-cli -am compile exec:java
```

Against a real vendor, it is one variable:

```bash
ANTHROPIC_API_KEY=… ./mvnw -q -pl :nessy-example-chat-cli -am compile exec:java
GEMINI_API_KEY=…    ./mvnw -q -pl :nessy-example-chat-cli -am compile exec:java
XAI_API_KEY=…       ./mvnw -q -pl :nessy-example-chat-cli -am compile exec:java
OPENAI_API_KEY=…    ./mvnw -q -pl :nessy-example-chat-cli -am compile exec:java
```

`NESSY_MODEL` names the model to use — required, since a provider's
`@AutoConfiguration` names no default. Set no key and the Boot context finds
no `InferenceProvider` bean at all, and the program says so instead of a stack
trace out of `main`.

Bedrock ships no `@AutoConfiguration`: ambient AWS credentials mean someone
once deployed something to AWS, not that they chose Bedrock for this. An
application that wants it constructs it explicitly.

`/quit` or Ctrl-D leaves.
