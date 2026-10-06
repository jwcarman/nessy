# Nessy Example: Chat CLI

An ordinary Spring Boot application that happens to be a terminal, standing on
the Nessy starter: a conversation, a notebook, a plan, and one tool a person has
to approve. The starter supplies the direct-door factory and registers the
providers; the notebook and the plan install themselves from the `nessy-memory-notebook` and `nessy-planning` modules this pom declares (`nessy.notebook.enabled=false` and `nessy.plan.enabled=false` turn them off); `NESSY_PROVIDER` and `NESSY_MODEL` choose which one answers.

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
- **A fact a tool could not fix.** The date is an ambient source, given to the model with
  every message. There was a
  `today` tool here first and it did not help: asked about Christmas shopping,
  the model named the wrong year and reasoned from it *without calling anything
  to check*. A tool only works if the model volunteers to use it, and that is
  precisely the failure where it does not.
- **A tool a person has to allow.** `send_email` is gated by
  `ConsoleApprover`, which asks right there at the prompt:

  ```
    ⚠ Send an email to jim@example.com, subject "Dinner", body: Are you free on Friday?
      allow? [y/N]
  ```

  It answers on the spot rather than deferring, because the person
  is at the keyboard with the agent's output still on screen. Anything but
  `y`/`yes` is a no, and end of input is a no — silence is not consent.
- **Streaming.** The answer is typed out as the model writes it.

**The conversation is kept with everything else.** The starter's JDBC backend
stores it in the same PostgreSQL as the notebook and the plan. Every launch
starts a new conversation and prints its id:

```
conversation 0198…: resume with --nessy.console.agent=0198…
```

`/clear` starts another one without leaving the program (the old one stays
resumable). To pick a conversation back up, pass its id, as an argument
(`-Dexec.args=--nessy.console.agent=<id>`) or as `NESSY_CONSOLE_AGENT=<id>`.

## Run it

Which model it talks to is not written down here. This example's classpath
carries the `openai` and `anthropic` inference adapters, so lighting a
preset with that vendor's key is the whole choice — `NESSY_PROVIDER` and
`NESSY_MODEL` say which one answers and with what.

Against [LM Studio](https://lmstudio.ai) or any other OpenAI-compatible local
runtime, which costs nothing, using the keyless `lmstudio` preset:

```bash
NESSY_PROVIDERS_LMSTUDIO_ENABLED=true \
NESSY_PROVIDER=lmstudio \
NESSY_MODEL=qwen/qwen3-coder-30b \
  ./mvnw -q -pl :nessy-example-chat-cli -am compile exec:java
```

Against a real vendor, name its preset, set its key, and name a model —
`chat-cli` needs both `NESSY_PROVIDER` and `NESSY_MODEL`:

```bash
NESSY_PROVIDER=anthropic ANTHROPIC_API_KEY=… NESSY_MODEL=claude-sonnet-5-5 \
  ./mvnw -q -pl :nessy-example-chat-cli -am compile exec:java
NESSY_PROVIDER=xai XAI_API_KEY=… NESSY_MODEL=grok-4 \
  ./mvnw -q -pl :nessy-example-chat-cli -am compile exec:java
NESSY_PROVIDER=openai OPENAI_API_KEY=… NESSY_MODEL=gpt-5.1 \
  ./mvnw -q -pl :nessy-example-chat-cli -am compile exec:java
```

`NESSY_PROVIDER` and `NESSY_MODEL` are a pair — set both or neither. Set no
key and the Boot context lights no preset at all, and the program says so
instead of a stack trace out of `main`.

Bedrock ships no preset: ambient AWS credentials mean someone once deployed
something to AWS, not that they chose Bedrock for this. An application that
wants it constructs it explicitly.

The commands work from the repository root or from this directory: the
`exec-maven-plugin` configuration names `compose.yaml` by absolute path, because
`exec:java` runs inside Maven's own JVM and Boot would otherwise look for it in
whatever directory Maven was started from.

`/quit` or Ctrl-D leaves.
