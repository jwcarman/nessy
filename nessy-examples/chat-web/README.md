# Nessy Example: Chat Web

The same conversation as `chat-cli`, in a browser, with the thing a terminal
cannot show well: **a tool that waits for a person, and keeps waiting if the
page closes.**

It consumes `nessy-spring-boot-starter`, so there is no engine wiring here.
What is left is the application: how it reaches a model, what its tools are, which of them needs a
person, and where that person is asked.

## What it shows

**The queued door.** `POST /api/agents/{id}/messages` tells the agent
and returns `202` with an empty body. The turn runs on the engine's own
threads, so no request is held while the model works. A conversation that
has ended answers a new message with `409`.

**One stream carries the rest.** The answer, the deltas as the model writes
them, and every step the agent takes arrive on `GET /api/agents/{id}/events`.
The stream is journaled. A browser that reconnects with `Last-Event-ID`
catches up on what it missed.

**Messages sent while the agent is working are batched.** They wait in the
agent's queue and are given to it together, as one message: joined with a
blank line, in the order they arrived, and answered in one turn
(`BacklogPolicy.mergeBy` in `ChatConfiguration`). A message to an idle agent
starts its turn at once.

**The approval waits in Nessy.** `send_email` is gated. Its approver defers
and keeps nothing, so the application holds no approval state. The approval
request waits in Nessy and outlives the page and the process.

A card is listed from `AgentWork.status(...).waitingApprovals()`, which the
page reads with `GET /api/agents/{id}`. That call also returns the
transcript. A decision is `POST /api/agents/{id}/approvals/{key}` with
`{"decision": "approve"}`, or anything else for a denial. The key is the
call's idempotency key, and it is the same after a restart. The controller
passes the decision to `Replies.approve(type, agent, key, result)`.
`Applied` is `202`. `Ignored` is `409`: the request was already answered,
its deadline passed, or the key is not waiting. A key that is not a UUID is
`400`. After any answer the page redraws its cards from the state.

**A person has five minutes.** `chat.approval-term` (`CHAT_APPROVAL_TERM`,
default `PT5M`) is how long. After it, the call is recorded as failed and
the card is gone.

**The endpoint decides who may answer.** Nessy does not check who is
answering. This example puts no login in front of the page's endpoint, so
an application that copies it must guard it.

Other endpoint: `DELETE /api/agents/{id}` ends the conversation. The story
is kept; the agent takes no more input.

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
appear. Close the tab and open it again: the card is still there.

Locally, `qwen/qwen3-coder-30b` answers well and makes its tool calls quickly,
but it does not write summaries: asked for one, it repeats the transcript. Have
a second model write them, and load both in LM Studio first, so that it keeps
both in memory instead of unloading one to load the other:

```bash
~/.lmstudio/bin/lms load qwen/qwen3-coder-30b --context-length 32768 -y
~/.lmstudio/bin/lms load google/gemma-4-e4b --context-length 32768 -y
CHAT_SUMMARY_MODEL_ID=google/gemma-4-e4b \
  ./mvnw -q -pl :nessy-example-chat-web spring-boot:run
```

| Variable | Default | What it sets |
|---|---|---|
| `CHAT_PROVIDER` | `lmstudio` | which registered provider answers |
| `CHAT_MODEL_ID` | `qwen/qwen3-coder-30b` | the model |
| `CHAT_MODEL_URL` | `http://localhost:1234/v1` | the base URL of the `lmstudio` preset |
| `CHAT_CHAPTER_TURNS` | `20` | turns per chapter |
| `CHAT_SUMMARY_MODEL_ID` | the agent's model | the model that writes chapter summaries, on the same provider |
| `SPRING_DATASOURCE_URL`, `_USERNAME`, `_PASSWORD` | the compose file's database | where the agents are kept |
| `CHAT_APPROVAL_TERM` | `PT5M` | how long a person has to answer an email approval request |
| `OTLP_TRACES_URL` | `http://localhost:4318/v1/traces` | where traces go |

OpenAI itself works too: export `OPENAI_API_KEY`, which lights the starter's `openai`
provider, and name that provider instead of the `lmstudio` default:

```bash
CHAT_PROVIDER=openai \
CHAT_MODEL_ID=gpt-4o-mini \
  ./mvnw -q -pl :nessy-example-chat-web spring-boot:run
```

Anthropic works the same way: export `ANTHROPIC_API_KEY` and name the `anthropic`
provider. This also closes a chapter of the conversation every four turns, so
a few messages are enough to see one cut and summarised:

```bash
CHAT_PROVIDER=anthropic \
CHAT_MODEL_ID=claude-sonnet-5-5 \
CHAT_CHAPTER_TURNS=4 \
  ./mvnw -q -pl :nessy-example-chat-web spring-boot:run
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
