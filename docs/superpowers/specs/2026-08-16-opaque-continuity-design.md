# Opaque Continuity — provider-issued state the store must echo

**Date:** 2026-08-16
**Status:** RATIFIED in conversation (owner: "I want to add the new grammar piece to support
the opaque state stuff").
**Design of record:** subordinate to `2026-08-09-nessy-agent-harness-design-v2.md`.

## 1. The principle, stated once

Providers issue opaque continuity tokens that must be returned verbatim when history is
replayed. The grammar already admits this — `ThinkingBlock` carries a `signature` because
Anthropic's extended thinking demands it. Google now attaches a `thought_signature` to
function calls (nessy's live validation hit the 400 that proves it), and OpenAI's Responses
API carries encrypted reasoning items: the concept is convergent across vendors. Nessy is
the system of record — a stateless reducer that cannot faithfully replay what the provider
needs echoed is incomplete, not clean. This design completes the concept in the grammar's
own vocabulary; nothing in it is named after any vendor.

## 2. The grammar change

`ToolUseBlock` gains one optional component, named exactly as `ThinkingBlock` named it:

- `signature` — a nullable `String` (base64 text form; providers with byte signatures encode
  at their mapping boundary): *an opaque provider-issued continuity token, stored with the
  block and returned verbatim on replay; absent for providers that issue none.*
- A convenience constructor/factory preserves every existing call site unchanged (absent
  signature); the canonical constructor accepts it. Javadoc explains the field with the
  vendor-neutral sentence above — the never-heard-of-Gemini test.
- **Equality:** the signature participates in record equality, deliberately. The block is
  constructed once, at stream time, and persisted; at-least-once re-drives replay the SAME
  stored value, so the transcript's no-stutter dedup and the fold's idempotency are
  untouched. The spec states this reasoning in `ToolUseBlock`'s javadoc so nobody "fixes"
  equality later.
- No general block-envelope abstraction: two blocks sharing a field with identical semantics
  is the pattern; a third occurrence may earn an interface, not sooner.

## 3. The event that feeds it

`ModelEvent.ToolUseEmitted` (spi.model) gains the same optional `signature` — the stream is
where the token is born, the fold carries it into the block. The loop's fold passes it
through untouched; no other loop change. Existing providers emit it absent (their mappings
change by exactly nothing — overloads keep old call sites compiling).

## 4. Persistence

`StateCodec` (nessy-jdbc) serializes the new optional field; absent stays absent (old stored
states deserialize unchanged — additive compatibility, no migration). The codec's
sealed-grammar coverage tests grow the signed-tool-use case. The transcript path uses the
same message serialization and needs no separate change beyond its round-trip test.

## 5. Testing seam

`ScriptedModelProvider` (nessy-testing) gains `toolUseSigned(id, name, args, signature)`
beside the existing `thinkingSigned` precedent, so the full capture→fold→store→replay→
request loop is testable offline in any module.

## 6. Gemini closes the loop (the motivating consumer)

- **Capture:** `GeminiStream` reads `thoughtSignature` off function-call parts, base64s it
  into `ToolUseEmitted.signature`.
- **Replay:** `GeminiRequests` sets the decoded signature on rebuilt functionCall parts when
  present. When ABSENT — histories predating this change, or messages authored by another
  provider in a mixed setup — it stamps Google's documented skip sentinel
  (`skip_thought_signature_validator`) so replays remain legal; the javadoc cites Google's
  thought-signatures doc and names the tradeoff (skipped validation = degraded reasoning
  continuity for that call only).
- README + providers guide: the honest note flips from "tool calls fail on 3.x" to "tool
  calls carry real signatures; pre-existing histories degrade gracefully via Google's
  sanctioned sentinel." Live validation status updated only after the owner's key says so.
- `THINKING` capability remains deferred and unadvertised — thought-part *display* is a
  separate feature; this design is about tool-call continuity only.

## 7. Testing

House rules throughout. Core: block/event optional-field construction + old-overload
compatibility; fold pass-through (signed emitted event → signed block in the folded
message); equality-includes-signature pinned with the replay-identity rationale in the test
name. JDBC: StateCodec round-trip signed/unsigned; transcript round-trip. Gemini: stream
capture (signed part → signed event), request replay (signed block → signed part; unsigned
block → sentinel part), both offline via the established seam fakes. Live: the existing
GeminiLiveTest tool round-trip becomes the acceptance test — run by the owner's key.

## 8. Out of scope

- Thought-part display / `THINKING` capability for Gemini.
- OpenAI Responses-API encrypted reasoning (its own generation when we adopt that API).
- Any general vendor-state envelope beyond the two signature fields.
