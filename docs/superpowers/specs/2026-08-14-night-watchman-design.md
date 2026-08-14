# The Night Watchman — design

**Date:** 2026-08-14
**Status:** APPROVED — 2026-08-14 (design reviewed in session; supersedes the
patient-researcher example, whose branch is archived at
`patient-researcher-archive` and whose spec is retired as UNBUILT)
**Builds on:** the Spring Boot starter (2026-08-13, shipped) and the durable
kernel's `Memory` seam (2026-08-12, shipped) — this example is the starter's
no-web dogfood and the first dogfood of a custom `Memory` implementation.

---

## 1. Purpose

Exhibit the **time-triggered agent pattern** with a runnable artifact: the
trigger event is the clock. A `@Scheduled(cron = …)` firing initiates each
turn — no human types, no webhook arrives, no job ripens. Three lessons in
one example, each absent from the family today:

- **time initiates the turn** — Spring's scheduling facility is the driver;
  the pattern readers copy is "wake → observe → judge → act or stay quiet",
- **one continuous conversation across firings** — the agent remembers its
  recent rounds, so trend judgment ("third consecutive climb") is real
  conversation state at work, not a bolted-on history query,
- **bounded memory** — the conversation runs indefinitely but its recalled
  context cannot grow without bound: a windowing `Memory` implementation is
  the first dogfood of the seam's "freedom of retention, rule of law at the
  border" promise.

Success criterion: `ANTHROPIC_API_KEY=… ./mvnw spring-boot:run` and watch
the log for five-to-ten minutes — terse all-quiet rounds, a remark that some
vital is trending, then an alarm — while the recalled context stays inside
its window the whole time.

## 2. The story

The night watchman walks the engine room on a schedule. Every firing
(default: the top of each minute), the scheduler tells the **same**
conversation `"It is 08:34 — do your rounds."`. The agent reads the vitals
via its `check_vitals` tool — synthetic metrics (boiler pressure, bilge
level, hull stress) produced by a seeded random walk — judges them against
its standing orders (the system prompt names the normal bands), and either
reports all-quiet in a sentence or calls `raise_alarm(severity, reason)`,
which logs at WARN, loudly and obviously fake (the coupon-tool ethos). The
bilge level's walk is deliberately biased upward so the demo has a
guaranteed arc: quiet rounds, a trend remark, an alarm, within roughly five
to eight minutes at the default cadence.

## 3. Module

`nessy-examples/night-watchman` (artifactId `nessy-example-night-watchman`,
deploy-skipped like its siblings). A long-lived **Spring Boot app with no
web at all**: `spring.main.web-application-type: none`,
`spring.main.banner-mode: off` (the log is the UI; keep it clean),
`@EnableScheduling` — the scheduler's non-daemon thread is what keeps the
JVM alive. Spring's default single-threaded `TaskScheduler` serializes
rounds: a slow round delays the next rather than overlapping it, which is
correct watchman behavior and needs no code.

Dependencies: `spring-boot-starter` (plain), `nessy-spring-boot-starter`,
`nessy-model-anthropic`, `logback-classic` (compile — Boot 4 ships no SLF4J
provider), the mockito-excluded `spring-boot-starter-test`. **No JDBC, no
Postgres, no Docker, no compose, no Testcontainers** — the starter's
defaults supply the in-memory substrate, making this the leanest Boot
example in the family and the only one whose entire test suite runs in the
offline default build. The examples matrix reads: chat-cli (plain +
interactive), chat-web (Boot web + HITL), night-watchman (Boot + scheduled
autonomy). Boot BOM confined in-module, the chat-web discipline.

Properties (the whole surface):

- `watchman.cadence` — cron expression, default `0 * * * * *` (each minute).
- `watchman.window` — recall window in messages, default `40`.

## 4. The bound — `WindowedMemory`

An example-owned `Memory` implementation, the example's second lesson made
code:

```java
public final class WindowedMemory implements Memory {
  private final Memory delegate = new ListMemory();
  private final int window;

  public WindowedMemory(int window) { this.window = window; }

  @Override public void remember(ConversationId id, Message message) {
    delegate.remember(id, message);
  }

  @Override public Context recall(ConversationId id) {
    return delegate.recall(id).keepRecent(window);
  }
}
```

Retention stays whole (the delegate keeps everything the JVM's lifetime
allows); the **border** is where the law applies: `recall` hands the loop a
context trimmed to the last `window` messages via `Context.keepRecent`,
which is pair-safe by construction — the trimmed context is always
wire-legal, no tool exchange ever split. The watchman's horizon is its
window: it remembers recent rounds, not its whole life — honest watchman
semantics, and the reason an endless conversation cannot grow the model
call. `keepRecent(n)` by message count was chosen over `limitTokens` at
review: simpler, deterministic, demo-legible.

## 5. Wiring

- **`WatchmanConfig`** — the example's ONE nessy bean: the agent.
  `harness.agent()`, model `claude-sonnet-4-5`, the standing-orders system
  prompt (name the normal bands; instruct terse all-quiet reports and
  decisive alarms), `.memory(new WindowedMemory(window))`, two tools granted
  `UsagePolicy.allow()` — no human in this loop, both tools return
  `Awaited.ready` (no parks anywhere in this example). `Harness` and
  `ModelProvider` arrive from the starter's autoconfiguration.
- **`Watchman`** — the component that owns the single `Conversation<String>`
  (minted at startup) and the round method, annotated
  `@Scheduled(cron = "${watchman.cadence:0 * * * * *}")`. A round is:
  format the prompt with the current time, `conversation.tell(prompt,
  observer)` with a TurnObserver that logs assistant text and tool calls via
  SLF4J, log the outcome status. The round method is also directly callable
  — the test's entry point, the scheduler being only a trigger.
- **`EngineRoom`** — the vitals. A seeded random walk (fixed default seed;
  the walk, not the seed, is configuration) producing the three metrics;
  bilge biased upward. `check_vitals` renders them as a compact readable
  string. Deterministic given the seed, so its unit test can assert the
  drift's direction without flakiness.
- **`CheckVitalsTool`**, **`RaiseAlarmTool`** — thin `Tool` implementations
  over `EngineRoom` and the log; both `Awaited.ready`.

## 6. Testing

All offline, no Docker, no tags — this module's whole suite runs in the
default `./mvnw -q clean verify`:

- **`WindowedMemoryTest`** (unit): recall is bounded (remember > window
  messages, recall ≤ window); a tool exchange straddling the cut survives
  or dies whole (pair-safety observed at this seam, not re-proved — one
  assertion that the returned context is constructible is enough, since
  `Context` validates itself).
- **`EngineRoomTest`** (unit): same seed → same readings; bilge trends
  upward over enough steps.
- **`NightWatchmanSmokeTest`** — plain `@SpringBootTest` (non-web), the
  chat-web scripted-provider pattern: a `@TestConfiguration` `Harness` bean
  over a scripted `ModelProvider` (wins by `@ConditionalOnMissingBean`; no
  key, no network), calling `watchman.round()` directly:
  - round one: scripted tool-use for `check_vitals` then an all-quiet
    sentence — asserts the round completes and the conversation id is
    stable across rounds,
  - a later round: scripted `raise_alarm` call — asserts the alarm tool
    executed (its effect recorded by a declared listener or the tool's own
    test-visible state),
  - after more rounds than a small configured window: `agent.contextFor`
    (or `snapshot`) shows recalled context ≤ window — the bound holds under
    the loop, not just in the unit test.
  - The scheduler itself is NOT under test (`watchman.cadence` set to a
    far-future cron in the test profile; Spring's own `@Scheduled` needs no
    re-proving).

## 7. Deliberately not built

Durability (in-memory is the point — the conversation honestly dies with
the JVM), any web surface, HITL/approvals, parks (both tools are `Ready`;
the machine-half verbs stay undemoed by ruling — recorded, not forgotten),
real alerting or paging, multi-schedule fan-out, `limitTokens`/token-aware
memory (the `contextWindow` dial stays reserved), and a summarizing memory
(the window IS the demo; summarization is a future Memory dogfood).

## 8. Resolved at review (2026-08-14)

1. Replaces patient-researcher (user ruling); that work is archived on
   `patient-researcher-archive`, and its autoconfigure ordering fix was
   cherry-picked to main independently (`c8d4514`).
2. One continuous conversation across firings, not fresh-per-firing.
3. In-memory substrate with a hard recall bound; `keepRecent(n)` over
   `limitTokens`.
4. Name: `night-watchman`.
