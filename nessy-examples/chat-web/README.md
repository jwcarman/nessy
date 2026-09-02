# Nessy Example: Chat Web

The same conversation as `chat-cli`, in a browser, with the thing a terminal
cannot show well: **a tool that waits for a person.**

It consumes `nessy-spring-boot-starter`, so there is no actor system here, no
cluster, no serializer bindings, no substrate. What is left is the
application: how it reaches a model, what its tools are, which of them needs a
person, and where that person is asked.

## What it shows

**The stream is not the response to your message.** POSTing a message returns
`202` and an empty body — by then the line is durably the agent's problem, not
the request's. Everything the agent then says arrives on a separate, standing
`EventSource` subscription to that agent. That is not a stylistic choice; it
is the only shape that matches the engine:

- a turn started in one tab is narrated to every tab
- an answer that lands while nobody is looking is not lost
- a tool that finishes an hour after the message that triggered it still has
  somewhere to report

**Approval is the agent waiting, not the request blocking.** `send_email` is
gated. When the model asks for it, the approver defers: it tells the desk
where the answer should come back and how long the question stands, then
returns. The turn stays parked — for an hour, across page reloads, across
tabs — until someone clicks. The reply token never reaches the browser; the
page addresses a question by its call id and the server looks the token up.

`send_email` sends nothing. It is the right *shape* — outward-facing and
irreversible — without being something you could point at a stranger.

## Run it

Defaults target [LM Studio](https://lmstudio.ai) on `localhost:1234`:

```bash
./mvnw -q -pl :nessy-example-chat-web -am spring-boot:run
```

Then open <http://localhost:8080>. Ask it to email someone and watch the card
appear.

Any OpenAI-compatible endpoint works:

```bash
CHAT_MODEL_URL=https://api.openai.com/v1 \
CHAT_MODEL_API_KEY=sk-… \
CHAT_MODEL_ID=gpt-4o-mini \
  ./mvnw -q -pl :nessy-example-chat-web -am spring-boot:run
```

## What it does not do

Two things are in memory, and the application says so at startup rather than
letting you find out:

- **Transcripts and backlogs.** No `DataSource` bean means the starter falls
  back to an in-memory substrate and warns loudly. Add one and it uses it.
- **Agent and turn state.** `application.conf` picks Pekko's in-memory
  durable-state store, because an example that needs a database before it will
  say hello is an example nobody runs. The `watchman` example next door
  answers both with Postgres, which is what a deployment does.

The reply key IS fixed, in `application.yml`, because ephemeral keys and
parked approvals do not mix: a token minted before a restart cannot be read
after one, and every waiting question becomes unanswerable. It is a demo key.
Generate your own.
