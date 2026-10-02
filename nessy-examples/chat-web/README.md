# Nessy Example: Chat Web

The same conversation as `chat-cli`, in a browser, with the thing a terminal
cannot show well: **a tool that waits for a person.**

It consumes `nessy-spring-boot-starter`, so there is no engine wiring here.
What is left is the application: how it reaches a model, what its tools are, which of them needs a
person, and where that person is asked.

## What it shows

**The answer is the response to your message.** The app runs on the direct
door. `POST /api/agents/{id}/messages` runs the turn on the request thread
and returns when it is over: `200` with `said`, `tokens` and `calls` for an
answer, `200` with `refused` for a refusal, `500` with `failed` when the
turn ended without one, and `409` when another request is already mid-turn
on that agent.

**The stream shows the turn happening.** A page also holds an
`EventSource` on `GET /api/agents/{id}/events`, which carries the deltas as
the model writes them. That stream is narration, not delivery: the answer
comes back on the POST, and a browser that reconnects with `Last-Event-ID`
catches up on what it missed.

**Approval holds the request.** `send_email` is gated. When the model asks
for it, the approver puts a card on the approvals stream
(`GET /api/agents/{id}/approvals/events`) and waits for a click, which the
page sends as `POST /api/agents/{id}/approvals/{callId}` with
`{"decision": "approve"}` or anything else for a denial. The waiting turn
holds the POST that started it. It waits at most five minutes; no answer in
that time is a denial. A second click on a question already answered gets
`409`.

The cards live in this process's memory. A restart loses them, and a turn
cannot outlive its request: the direct door never parks a call. A tool that
must wait for days needs the queued door.

Other endpoints: `GET /api/agents/{id}` returns the transcript and the
pending cards, and `DELETE /api/agents/{id}` ends the conversation (the
story is kept; the agent takes no more input).

`send_email` sends nothing. It is the right *shape* — outward-facing and
irreversible — without being something you could point at a stranger.

## Run it

Run the example from the repository root. `spring-boot:run` starts the
compose file beside this README (Postgres, and Grafana for traces) and stops it
on exit. Defaults target [LM Studio](https://lmstudio.ai) on `localhost:1234`:

```bash
./mvnw -q -pl :nessy-example-chat-web -am install -DskipTests
./mvnw -q -pl :nessy-example-chat-web spring-boot:run
```

The `install` builds the example and everything it depends on; `spring-boot:run`
then runs the example alone. Run the `install` again after changing any module.
The compose file publishes Postgres on port 5432, so the example cannot run
beside another one that does the same.

Then open <http://localhost:8080>. Ask it to email someone and watch the card
appear.

| Variable | Default | What it sets |
|---|---|---|
| `CHAT_PROVIDER` | `lmstudio` | which registered provider answers |
| `CHAT_MODEL_ID` | `qwen/qwen3.6-35b-a3b` | the model |
| `CHAT_MODEL_URL` | `http://localhost:1234/v1` | the base URL of the `lmstudio` preset |
| `CHAT_CHAPTER_TURNS` | `20` | turns per chapter |
| `SPRING_DATASOURCE_URL`, `_USERNAME`, `_PASSWORD` | the compose file's database | where the agents are kept |
| `OTLP_TRACES_URL` | `http://localhost:4318/v1/traces` | where traces go |

OpenAI itself works too: export `OPENAI_API_KEY`, which lights the starter's `openai`
provider, and name that provider instead of the `lmstudio` default:

```bash
CHAT_PROVIDER=openai \
CHAT_MODEL_ID=gpt-4o-mini \
  ./mvnw -q -pl :nessy-example-chat-web spring-boot:run
```

Anthropic works the same way: export `ANTHROPIC_API_KEY` and name the `anthropic`
provider. This also turns on the prompt cache and closes a chapter of the
conversation every four turns, so a few messages are enough to see one cut and
summarised:

```bash
CHAT_PROVIDER=anthropic \
CHAT_MODEL_ID=claude-sonnet-5-5 \
CHAT_CHAPTER_TURNS=4 \
  ./mvnw -q -pl :nessy-example-chat-web spring-boot:run \
  -Dspring-boot.run.arguments="--nessy.providers.anthropic.properties.anthropic.cache_control.ttl=FIVE_MINUTES"
```

`CHAT_CHAPTER_TURNS` is how many turns make a chapter; it defaults to the
engine's 20. A chapter holds at most 30 turns unless `maxChapterLength` is
raised, which this app does not do.

## What it does not do

**It needs Postgres.** Agents, their stories, notes, plans and
outstanding work are all rows. The `docker-compose.yml` beside this README runs
the database the defaults point at (`localhost:5432/nessy`, user `nessy`, password `nessy`);
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and
`SPRING_DATASOURCE_PASSWORD` point it somewhere else.
