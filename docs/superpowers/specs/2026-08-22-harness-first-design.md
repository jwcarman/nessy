# Harness First — the agent API people imagine

**Date:** 2026-08-22
**Status:** Ratified (James, in conversation, 2026-08-22)
**Amends:** `2026-08-18-agent-as-scope-design.md` §10.11 (the tier boundary
moves: the harness absorbs the host tier's machinery) and
`2026-08-22-durable-deliveries-design.md` (the worker/desks/reaper's home).
**Non-goals here:** the full-mapper-weave task and the seam-roster kills
remain separately parked on the decision list; this reform neither executes
nor forecloses them.

---

## 1. The sentence

Ask Nessy for a harness; keep it forever; bind any id into a transient agent;
tell it things. Durability is a property of the substrate, not the API:

```java
var harness = Nessy.harness(h -> h             // built once, kept — immortal
        .provider(provider)                    // the one required dependency
        .systemPrompt("You are the ops assistant.")
        .tools(restart, diagnose)              // bare tools, allow-by-default
        .substrate(jdbc));                     // default: in-memory

harness.bind(AgentId.of("ops-agent-1")).observe("restart prod-eu");
```

The identical program is a toy on the in-memory substrate and a durable,
resumable, any-host system on JDBC — one line differs. That is the whole
pitch, and the getting-started page opens with it.

## 2. The door

`Nessy` is the single front door — one class to remember, autocomplete as the
tour:

- `Nessy.harness(HarnessCustomizer<String>)` — the String-observation
  five-minute path.
- `Nessy.harness(Class<O>, HarnessCustomizer<O>)` — typed observations;
  requires `.renderer(...)`.
- `Nessy.cli()` — the ephemeral REPL door, unchanged by this reform.

**The customizer is the house style** — the same grammar `Tool.of(type,
customizer)` already teaches: the lambda receives a `HarnessConfig<O>` (the
old `AutonomousBuilder`, renamed; identical setters, no terminal `build()`),
the lambda closes, and Nessy constructs — atomically, internally. No
half-configured builder object ever exists in user hands, which makes "Nessy
is the harness's only compiler" true by shape rather than by javadoc (the
law stays stated on `Harness.of(...)` regardless). `HarnessCustomizer<O>` +
`HarnessConfig<O>` mirror `ToolCustomizer` + `ToolConfig`, one lesson for
both. Other assembly styles (framework wiring, conditional configuration)
compose inside the lambda; there is deliberately no second door.

## 3. The builder minimum

- `.provider(ModelProvider)` — required, explicit (no env fallback; the one
  true dependency stays visible).
- `.systemPrompt(String)` — first-class sugar; the full `ModelSettings`
  object remains for model id / max-tokens / the rest.
- `.tools(Tool...)` — allow-by-default sugar (the same allow-sugar the
  Spring design specified for bare Tool beans); `.grants(ToolGrant...)`
  stays beside it as the governed path; docs teach the graduation.
- `.type(...)` — defaults to `"agent"`. **One harness per agent type per
  substrate** is the user's contract (two same-type harnesses over one
  substrate would double-drain deliveries); stated in the builder javadoc
  and the docs.
- `.substrate(Substrate)` — defaults to a fresh `InMemorySubstrate`.
- Everything else (mapper, staleness, capacity, observers, notifier) keeps
  its current default and seam.

## 4. The harness

- **Immortal, not closeable.** The harness is the thing you maintain access
  to. Its life-support — the delivery worker, the reaper sweep, executor
  pools — runs on daemon threads and lives exactly as long as the process.
  No `AutoCloseable`, no try-with-resources in any example. One undecorated
  lifecycle method, `shutdown()`, exists for infrastructure (Spring destroy
  callbacks, test teardown) and is documented as such — quiescing the
  worker while the harness is reachable is a container's job, never
  application hygiene.
- **The host tier's machinery moves in**: the worker, the approval and
  completion desks, and the reaper are harness-owned. The doors surface on
  the harness: `harness.approvals()`, `harness.completions()`.
  `AutonomousHost` is deleted; §10.11's vocabulary amends — a harness is
  the compiled recipe *plus its life-support*; "host" retires to meaning
  your process.
- **`bind(AgentId)` returns the `Agent<O>`** — the harness pairs itself
  with the id-stamped stores internally. `Binding` leaves the public
  surface (internal record; the worker's fold machinery keeps a
  package-visible seam to the raw stores). Agent handles are plain objects:
  transient by contract, never closeable — they hold nothing, so there is
  nothing to leak by dropping them, which is the feature.

## 5. Type-filtered sweeps (new law)

When multiple harnesses share one substrate, the outbox and computation
keyspaces are shared. Each harness's worker and reaper MUST sweep only its
own type's records: a delivery's routing data and a computation's derived
address both carry `agentType`, and the sweep skips foreign types before
reading further. With the filter: same type ⇒ same harness ⇒ one worker ⇒
the in-JVM claim covers racing drains; different types never touch each
other's records. (Cross-process same-type remains the parked §6.6 lease,
unchanged.)

## 6. What dies

`AutonomousHost` (public type), `Binding` (public surface), the grant-ceremony
requirement on the five-minute path, `post(String, ...)` as the tell verb
(binding + `observe` is the story; prose may say "tell"), and the pretense
that the harness needs a compiler other than Nessy.
