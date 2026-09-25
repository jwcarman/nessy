# Nessy Example: Chat CLI

An ordinary Spring Boot application that happens to be a terminal: a
conversation, a notebook, a plan, and one tool a person has to approve.

Nothing to start first. `compose.yaml` beside this file is brought up when the
example runs and stopped when it exits, so the database the notebook and the
plan live in is part of running it rather than a prerequisite.

## What it shows

It runs on the **direct** harness: `ask` runs the turn on the calling thread and
returns what it came to. The terminal has nothing to wait on and nothing to
guess about — it used to block five minutes on a narration queue and then admit
it could not tell whether the model was still working or the news had been lost.

`nessy-console` owns the loop and the printing. What is left here is the part
that is about THIS program: what it is for, and what it can do.

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

**The conversation does not survive the process** — a terminal's chat lives as
long as the terminal does, and a CLI that silently resumed yesterday's would
surprise the person typing into it. What the notebook and the plan keep is the
part worth outliving it. Point `chat-web` at a database to see the other half.

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
