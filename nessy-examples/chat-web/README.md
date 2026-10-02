# Nessy Example: Chat Web

The same conversation as `chat-cli`, in a browser, with the thing a terminal
cannot show well: **a tool that waits for a person.**

It consumes `nessy-spring-boot-starter`, so there is no engine wiring here.
What is left is the application: how it reaches a model, what its tools are, which of them needs a
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

Start its database and Grafana with the compose file beside this README, then
the example from the repository root. Defaults target
[LM Studio](https://lmstudio.ai) on `localhost:1234`:

```bash
docker compose -f nessy-examples/chat-web/docker-compose.yml up -d
./mvnw -q -pl :nessy-example-chat-web -am install -DskipTests
./mvnw -q -pl :nessy-example-chat-web spring-boot:run
```

The `install` builds the example and everything it depends on; `spring-boot:run`
then runs the example alone. Run the `install` again after changing any module.

Then open <http://localhost:8080>. Ask it to email someone and watch the card
appear.

OpenAI itself works too: export `OPENAI_API_KEY`, which lights the starter's `openai`
provider, and name that provider instead of the `lmstudio` default:

```bash
CHAT_PROVIDER=openai \
CHAT_MODEL_ID=gpt-4o-mini \
  ./mvnw -q -pl :nessy-example-chat-web spring-boot:run
```

## What it does not do

**It needs Postgres.** Agents, their stories, notes, plans and
outstanding work are all rows. The `docker-compose.yml` beside this README runs
the database the defaults point at (`localhost:5432/nessy`, user `nessy`, password `nessy`);
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and
`SPRING_DATASOURCE_PASSWORD` point it somewhere else.

The reply key IS fixed, in `application.yml`, because ephemeral keys and
parked approvals do not mix: a token minted before a restart cannot be read
after one, and every waiting question becomes unanswerable. It is a demo key.
Generate your own.
