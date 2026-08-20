# The Three Front Doors — the conversation store rework

**Date:** 2026-08-14
**Status:** APPROVED — 2026-08-14 (design reviewed in session)
**Builds on:** the durable kernel (2026-08-12, shipped) and the Spring Boot
starter (2026-08-13, shipped). Amends the design of record's store chapter;
this spec is the store's design of record from here.

**The aesthetic bar, binding on all work under this spec:** code reads like
well-written prose — "baby code," no tricks, no clever constructs. Each
interface must be describable in one sentence a newcomer believes.

---

## 1. Purpose — the complaint, and what answers it

`ConversationStore` is busy. Six methods serve two different callers: the
loop driving a conversation (`load`, `save`, `appendAgenda`) and the
callback door answering the world (`findPark`, `findParkConversation`,
`consumeToken`). Its `save` contract does three things in one atomic act
(CAS the control block, drain the agenda, sync the park index). The state
carries token bookkeeping its own logic never uses — the fold pairs by call
id everywhere (`removeFirstMatchParked` matches `call.id()`); tokens ride
along only so `save` can derive an index for a *different* caller. And the
message log lives in a `AgentMemory` implementation that is secretly a versioned
table (`nessy_memory (conversation_id, seq, message)`) nobody can read for
audit or pagination because it has no name.

The rework names the concepts and gives each its own small front door:

- the **ConversationStore** keeps a conversation's control block and its
  inbox;
- the **Parks** registry answers the callback door;
- the **Transcript** remembers what was said — for memories, audits, and
  scrolling humans.

One implementation module may serve all three over one database; each
*contract* tells one story.

## 2. The Transcript (spi.memory — it is the memory jurisdiction's own storage primitive; ruled at review)

An append-only, versioned, per-conversation message log — the storage
primitive some memories are based on, and the read surface audit and chat
history need.

```java
public interface Transcript {

  /** One entry: the message and the monotonic per-conversation version it landed at. */
  record Entry(long version, Message message) {}

  /**
   * Appends unless {@code message} equals the current last entry — the at-least-once
   * re-telling rule (a transcript does not stutter). Returns the entry either way:
   * the new one, or the existing last it deduplicated against.
   */
  Entry append(ConversationId id, Message message);

  /** The whole log, in version order. */
  List<Entry> all(ConversationId id);

  /** The tail: every entry with version strictly greater than {@code afterVersion}. */
  List<Entry> tail(ConversationId id, long afterVersion);

  /**
   * The scroll-up page: up to {@code limit} entries with version strictly less than
   * {@code beforeVersion}, in version order (the caller prepends them above what it
   * already shows).
   */
  List<Entry> page(ConversationId id, long beforeVersion, int limit);

  static Transcript inMemory() { ... }
}
```

Rulings folded in:

- **The no-stutter rule is the transcript's own append contract**, not the
  memory's: it is where `JdbcMemory`'s `SELECT … FOR UPDATE` serialization
  already lives, it is atomic only inside the implementation, and audit
  readers do not want duplicate rows either. `JdbcTranscript` keeps exactly
  that locking discipline; `InMemoryTranscript` keeps `ListMemory`'s
  compute-under-the-key discipline.
- The transcript stores **raw tellings** — open tails included. `Context`
  assembly, wire legality, and the open-tail trim are Memory's border law
  (§3), not the transcript's. An auditor sees what was actually told.
- Implementations: `InMemoryTranscript` (nessy-core, the
  `Transcript.inMemory()` default) and `JdbcTranscript` (nessy-store-jdbc)
  over the table `nessy_memory` already is, renamed `nessy_transcript`
  (`conversation_id, version, message`, PK `(conversation_id, version)`;
  `seq` renames to `version` — it was always one).

## 3. TranscriptMemory — two memories become one

`ListMemory` and `JdbcMemory` differ only in where their rows live; their
*memory policy* — verbatim retention, consecutive-dup idempotency, open-tail
trim at recall — is identical, line for line. They dissolve into one:

```java
public final class TranscriptMemory implements Memory {

  private final Transcript transcript;

  public TranscriptMemory(Transcript transcript) { ... }

  @Override public void remember(ConversationId id, Message message) {
    transcript.append(id, message);   // idempotency is the transcript's no-stutter rule
  }

  @Override public Context recall(ConversationId id) {
    List<Message> messages = transcript.all(id).stream().map(Transcript.Entry::message).toList();
    return Context.of(withoutOpenTail(messages));
  }
}
```

`withoutOpenTail` moves here once, verbatim from the two copies it replaces
(its javadoc's halt-while-parked caveat travels with it — that recorded
follow-up is unchanged by this spec). The `AgentBuilder` default memory
becomes `new TranscriptMemory(Transcript.inMemory())`. `ListMemory` and
`JdbcMemory` are deleted, not deprecated (pre-1.0).

## 4. SummarizingMemory — the tail API's dogfood

The motivating composition, shipped this generation so `tail(id, version)`
earns its shape by use.

- **`SummaryStore`** (spi.memory): a per-conversation summary record —
  `record Summary(long watermark, String text)` — with `find(id)` and
  `save(id, Summary)` (last write wins; no fencing — a lost summary write
  is re-summarized work, never lost words, because the words live in the
  transcript). Implementations: in-memory (core) and JDBC
  (`nessy_summary (conversation_id PK, watermark, summary)`).
- **`SummarizingMemory`** (spi.memory): wraps a `Transcript`, a
  `SummaryStore`, a `ModelProvider` + model name + summarization prompt,
  and a tail threshold. `remember` appends to the transcript. `recall`:
  load the summary (absent → nothing folded yet, an internal
  before-version-0 sentinel that is never persisted — transcript versions
  start at 0, so a literal watermark of 0 would silently claim the first
  message as already summarized; amended 2026-08-14 when implementation
  proved the original "absent → watermark 0" wording wrong); load
  `tail(id, watermark)`; if the tail exceeds the threshold, one model call
  folds summary + tail into a new summary, saved with the tail's last
  version as the new watermark, and the tail reloads from there; the
  returned context is the summary rendered as one opening user message
  (skipped when empty) followed by the tail's messages, open-tail-trimmed,
  through `Context.of`. The watermark IS the bookkeeping: crash anywhere
  and the next recall re-summarizes the same tail — idempotent because the
  transcript is the truth.
- Its model spend never touches `ConversationState.usage` — the existing
  jurisdiction ruling (design §10.6) already covers it. (Amended
  2026-08-14: the original draft also promised the call "instrumented on
  its own observation span"; the shipped constructor deliberately takes no
  `ObservationRegistry`, so that instrumentation is future work, recorded
  here rather than silently dropped.)
- Summarization must keep tool exchanges whole in what it leaves behind:
  the boundary it summarizes up to is chosen pair-safely (the same genuine
  user-turn rule `Context.pairSafeCut` embodies), so the remaining tail is
  always a legal context suffix.

## 5. Parking, evicted from the conversation

The conversation stops knowing about tokens. It knows what it must:
*call c1 is still outstanding.*

**State.** `ConversationState.parkedCalls` becomes `List<ToolCall>`. The
`parked(call, token)` transition becomes `parked(call)`. The `ParkedCall`
record leaves the state's vocabulary and becomes the parks registry's card
(§6). The fold's pairing logic is untouched — it already matched by call id.

**Mail.** `InboxEntry.Resolved` (né `AgendaItem.Resolved`) carries
`(callId, resolution)` instead of `(token, resolution)`. The loop's
resolution routing matches the call id against `parkedCalls`; a resolution
whose call is no longer outstanding drains quietly as stale mail — exactly
today's behavior, re-keyed.

**The registry** (spi.conversation, beside the store):

```java
public interface Parks {

  /** A parked wait, as the registry knows it: whose conversation, which call, which token. */
  record Park(ConversationId conversationId, ParkToken token, ToolCall call) {}

  /** Registers a wait. Idempotent on token (at-least-once loop retries re-register). */
  void park(Park park);

  /** The callback door's translation: token → the wait it names. */
  Optional<Park> find(ParkToken token);

  /** Every wait ever registered for {@code id} — the approval-card read, filtered by the caller. */
  List<Park> forConversation(ConversationId id);

  static Parks inMemory() { ... }
}
```

**Ordering is forced, not chosen.** The loop's park chokepoint
(`applyParked`) registers the park **before** the fenced save. The tool has
already handed the token to the outside world (it returned
`Awaited.parked(token)` after submitting its job), so a lost registry entry
would strand a token the world holds — a wedged conversation. A registry
entry whose save then loses the fence (or never lands) is merely an orphan:
its resolution translates, appends mail addressed to a call that is not
outstanding, and drains as stale. Orphans are tolerated, not prevented.
Narration (`TurnEvent.ToolCallParked`) still fires only after the save
commits, unchanged.

**`consumeToken` dissolves.** Registry entries survive resolution (they are
the durable record that this token once named this wait — the same
keep-forever posture today's `nessy_token` table already had). A redelivered
resume translates the token again, appends another `Resolved`, and the
fold's is-this-call-still-outstanding check ignores it. Replay protection is
a property of the fold, not a claim protocol. Single-use stops being a
store method because it was always really a question about the
conversation: *is anyone still waiting on this?*

**The callback door itself** (`Harness`, signatures unchanged):

- `resume(token, resolution, observer)`: `parks.find(token)` →
  `UnknownParkTokenException` on a miss; append
  `InboxEntry.resolved(park.call().id(), resolution)` to the conversation's
  inbox; drive. Idempotent redelivery re-drives and reads current truth,
  as today.
- `progress(token, message)`: `parks.find(token)`; then load the state and
  emit `ToolProgress` only if the call is still outstanding — a settled
  wait returns `false`, narration dropped, exactly today's contract.
- `peek(token)`: the registry read, exposed.
- The single-agent-per-harness restriction and its loud
  `IllegalStateException`s are unchanged (multi-agent routing remains a
  recorded future spec).

## 6. The store, slimmed

```java
public interface ConversationStore {

  record Loaded(ConversationState state, List<InboxEntry> inbox) {}

  Optional<Loaded> load(ConversationId id);

  /**
   * The fenced save: persists state iff the stored version matches, bumping it and
   * deleting the drained mail — one atomic act, and nothing else.
   */
  ConversationState save(ConversationState state, Collection<String> drainedMail);

  /** Unconditional, atomic, never contended with saves. */
  void append(ConversationId id, InboxEntry entry);

  static ConversationStore inMemory() { ... }
}
```

Everything that enters a conversation lands in its inbox first; whoever
drives next reads the mail. `AgendaItem` renames to `InboxEntry`
(`Told`/`Resolved` variants keep their names), `appendAgenda` → `append`,
`Loaded.agenda` → `Loaded.inbox`, and the loop's internal vocabulary
(`drained`, comments) follows. `save` loses the park-sync clause entirely —
its atomicity story is now one sentence. The JDBC schema drops `nessy_park`
and `nessy_token`; `nessy_agenda` renames to `nessy_inbox` (same shape);
`JdbcParks` owns a new `nessy_parks (token PK, conversation_id, call)` with
an index on `conversation_id`.

## 7. Reads and wiring

- **`Agent.snapshot`** composes the approval-card view: load state; cards =
  `parks.forConversation(id)` filtered to parks whose `call.id()` is in
  `state.parkedCalls()`, rendered as today's `(token, call)` pairs. The
  `ConversationSnapshot` shape — status, parked calls with tokens, context
  — is unchanged, so **chat-web's JSON, UI, and smoke test change only in
  wiring, not behavior**.
- **`Harness`/`HarnessBuilder`**: the harness gains the `Parks` seam
  (substrate, defaulting to `Parks.inMemory()`, overridable via
  `HarnessBuilder.parks(...)`); `AgentBuilder.build()` threads it to the
  loop and the `Agent` the same way the store travels today.
- **`JdbcPersistence`** bundles all three doors (store, parks, transcript)
  plus the summary store; `nessy-autoconfigure` grows `Parks` and
  `Transcript` beans under the same classpath-and-datasource rules as
  today's store/memory beans, each yielding to a user-declared bean; the
  `AgentMemory` bean becomes `TranscriptMemory` over the `JdbcTranscript`.
  `NessyAutoConfiguration` passes the `Parks` bean into the harness.
- The examples: chat-web recompiles against renamed types with no
  behavioral change; night-watchman's `WindowedMemory` delegates to
  `new TranscriptMemory(Transcript.inMemory())` instead of `new
  ListMemory()`; chat-cli is untouched except type names if it references
  any.

## 8. Testing

- The store TCK (`ConversationStoreContract`) splits into three contracts —
  store (load/save/append fencing and drain atomicity), parks (register /
  find / for-conversation / idempotent re-register), transcript (append,
  no-stutter, tail, page, version monotonicity) — each run against the
  in-memory and JDBC implementations (JDBC behind `@Tag("container")`, as
  today).
- `SummarizingMemory` is tested offline with a scripted provider: the
  summary call is the script; asserts watermark advance, tail reload,
  crash-idempotency (re-recall after a "lost" summary save re-summarizes),
  pair-safe boundary choice, and the jurisdiction rule (state usage
  untouched).
- The kernel's park/resume/progress tests re-target the new seams; the
  loop's stale-mail routing gains a case for a resolution addressed to a
  settled call (the dissolved `consumeToken`'s semantics, now fold-owned).
- chat-web's smoke test must pass **unmodified in its assertions** — the
  park → approve → complete story is the invariant the rework must not
  bend. Offline reactor `verify` stays green with no Docker and no key.

## 9. Breaking (pre-1.0), stated loud

All deliberate, in-development shape changes; nothing below breaks a
shipped version, because none exists.

1. `ConversationStore` loses `findPark`, `findParkConversation`,
   `consumeToken`; `appendAgenda` → `append`; `Loaded.agenda` →
   `Loaded.inbox`.
2. `AgendaItem` → `InboxEntry`; `Resolved` re-keyed `(callId, resolution)`.
3. `ConversationState.parkedCalls` becomes `List<ToolCall>`;
   `parked(call, token)` → `parked(call)`. **Durable states serialized
   under the old shape do not deserialize under the new one; no migration
   code ships (pre-1.0), noted in the CHANGELOG.**
4. `ParkedCall` is replaced by `Parks.Park` (registry) and the snapshot's
   `(token, call)` card shape (which keeps the `ParkedCall` name and
   record definition, now sourced from the registry).
5. `ListMemory` and `JdbcMemory` are deleted in favor of
   `TranscriptMemory` over a `Transcript`.
6. `nessy_memory` renames to `nessy_transcript` (`seq` → `version`);
   `nessy_agenda` → `nessy_inbox`; `nessy_park`/`nessy_token` dropped;
   `nessy_parks` and `nessy_summary` added. Fresh bootstrap only; no data
   migration.

## 10. Deliberately not built

Registry row cleanup/TTL (operational, like today's `nessy_token` growth),
transcript retention/compaction policy, a paginated chat-history web
endpoint (the `page` read ships proven by tests; chat-web may demo it in a
later generation), token-aware summarization budgets (`contextWindow`
stays reserved), a standalone always-on transcription service (transcripts
arrive by choosing transcript-backed memories — the ruling), fencing on
`SummaryStore` (lost writes are re-done work), and multi-agent park
routing (unchanged, still a future spec).

## 11. Resolved at review (2026-08-14)

1. Agenda stays — it is the write-model's other half (tell-while-parked
   depends on it) — renamed **inbox** throughout.
2. Transcript writes are Memory-owned; audit/display arrive by choosing
   transcript-backed memories, not by a second always-on write path.
3. `SummarizingMemory` ships this generation as the tail API's dogfood.
4. Tokens evicted from `ConversationState`; `Parks` registry owns
   correlation; `consumeToken` dissolves into the fold's
   is-this-call-outstanding check (user's design, adopted over the earlier
   interface-segregation-only variant).
5. Register-before-save ordering (forced by the token already being in the
   world's hands); orphans tolerated as stale mail.
6. The no-stutter rule is the Transcript's append contract; the open-tail
   trim stays at Memory's border.
7. The Transcript lives in `spi.memory`, not its own package — it is the
   memory jurisdiction's storage primitive, and the package should read as
   one story: `AgentMemory`, `Transcript`, `TranscriptMemory`, `SummaryStore`,
   `SummarizingMemory`.
