# Examples

`nessy-examples` is a family of small, runnable applications, each built to
teach one thing well rather than to look impressive. Every module has its
own README with the exact run command; this page is the tour.

| Example | What it teaches |
|---|---|
| [hello](https://github.com/jwcarman/nessy/tree/main/nessy-examples/hello) | The smallest possible agent: the root README's five-minute snippet, made runnable. Builds a harness over a scripted model provider, grants one `add` tool, and asks "what is 2+2?" — no key, no network, no Docker, so it runs the same way everywhere, including CI. |
| [chat-cli](https://github.com/jwcarman/nessy/tree/main/nessy-examples/chat-cli) | The interactive terminal front door, built entirely on `nessy-console`'s `ConsoleRepl`: live-streaming assistant text, an approval gate, provider switching by environment variable (`fromEnv()`), and the plan facility's first public demonstration — a granted `update_plan` tool whose checklist rides every recall and prints in the terminal once per turn. |
| [scout](https://github.com/jwcarman/nessy/tree/main/nessy-examples/scout) | The tool-import showcase: an MCP toolbox (DeepWiki's public server) granted tool-by-tool, one of the three gated behind human approval so a person reads a *remote* server's tool call before it runs. Adopts the plan facility too, since researching a wiki genuinely is multi-step work. |
| [chat-web](https://github.com/jwcarman/nessy/tree/main/nessy-examples/chat-web) | The durable kernel end to end, browser-faced: a real Postgres-backed `ConversationStore` and `Memory`, a tool gated behind an approval that parks across a restart, and full observability — `nessy-spring-boot-starter` autoconfigures every substrate bean, so the application declares exactly one bean of its own, the agent. |
| [night-watchman](https://github.com/jwcarman/nessy/tree/main/nessy-examples/night-watchman) | The clock as trigger: `@Scheduled` firing wakes the same conversation round after round, so trend judgment across firings is conversation state at work. Also the bounded-recall `Memory` seam — a `keepRecent` stage over an in-memory transcript keeps an endless conversation's context from growing without bound. |
| [dispatcher](https://github.com/jwcarman/nessy/tree/main/nessy-examples/dispatcher) | The webhook trigger, both directions of the inbox over plain `curl`: `POST /signals` for the world volunteering news, `POST /callbacks/{token}` for the world answering a question. The headline scene kills the app after a tool parks and restarts it, then answers the callback in a fresh process that never saw the original signal — `JdbcParks` earning its keep. |
| [order-desk](https://github.com/jwcarman/nessy/tree/main/nessy-examples/order-desk) | The queue as trigger, and the first typed-vocabulary agent in the family (`harness.agent(OrderEvent.class)` over a sealed event grammar): a RabbitMQ message initiates a turn, external identity (the order id) mints the conversation, and a restart-and-redeliver scene shows at-least-once delivery honestly — a redelivered resolution drains as stale mail, a redelivered order event is genuinely re-told. |
| [newsroom](https://github.com/jwcarman/nessy/tree/main/nessy-examples/newsroom) | Subagents: a `writer` delegates research to a `researcher` it defines right inside its own `.subagent(...)` call. When the researcher's gated `ask_question` tool parks for human approval, the writer's own delegation call parks right alongside it — a real Postgres-backed restart scene finishes the delegation in a fresh process. Both agents share one `Notebook` over a fixed `SubjectId` for cross-agent continuity. |

## Running one

Each README states its own prerequisites plainly. `hello` and `chat-cli`
need nothing beyond a JDK (and, for `chat-cli`, a provider API key).
`scout` needs a provider key and network access to DeepWiki's public
server. `chat-web`, `dispatcher`, `order-desk`, and `newsroom` need a
provider key and Docker, for Postgres and (for `order-desk`) RabbitMQ.
`night-watchman` needs only a provider key.

## Where next

- [Getting Started](../guides/getting-started.md) — the smallest agent,
  explained line by line, the same shape `hello` runs.
- [Triggers](../guides/triggers.md) — the five trigger shapes this family
  demonstrates, one guide covering all of them together.
- [Durable Persistence](../guides/durable-persistence.md) — the JDBC wiring
  `chat-web`, `dispatcher`, and `order-desk` all build on.
