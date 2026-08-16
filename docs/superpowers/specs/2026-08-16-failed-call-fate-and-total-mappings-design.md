# Failed-call fate and total mappings

**Date:** 2026-08-16
**Status:** RATIFIED in conversation (owner: "yes, please do").
**Evidence:** live xAI trace (403 → "try again" → ClassCastException) + offline
reproduction, `.superpowers/cast-bug-investigation.md` (gitignored investigation record).
**Design of record:** subordinate to `2026-08-09-nessy-agent-harness-design-v2.md`.

## 1. The two defects, named

**Bug A — a failed model call leaves a zombie turn.** `ProviderModelCallExecutor`
catches only `ContextOverflowException`; any other `RuntimeException` from
`provider.stream(...)` or stream iteration propagates raw. `ModelCallFailed` never
folds, the conversation sticks at `AWAITING_MODEL`, and the next `tell` lands in
`told` (mid-turn interjection) instead of opening a turn. The durable state lies:
the user saw "call failed," the store says "still awaiting the model."

**Bug B — two mappings crash on legal grammar.** The reducer's `toolFinished` flush
deliberately rides accumulated `told` notes beside tool results in one USER message
("a dying conversation still delivers the world's words" — same pattern in `halted`).
`Message.user(List<ContentBlock>)` is public; custom transformers can build mixed
messages too. The shape is legal grammar. `OpenAiRequests.toUserRoleMessageParams`
(:106-115) and `GeminiRequests.toUserContent` (:172-184) both assume
"any `ToolResultBlock` present → every block is one" and cast the lot —
`ClassCastException` on the `TextBlock`. `AnthropicRequests` switches per block and
is correct.

## 2. The rulings

1. **Failed calls fold, they don't leak.** The executor converts any
   `RuntimeException` from the provider call or stream iteration into
   `ConversationEvent.ModelCallFailed` — the same door `ContextOverflowException`
   already uses — with a reason of the form `<ExceptionClassName>: <message>`, and
   logs the full exception at ERROR (the reason string is for the record; the log is
   for the operator; programming bugs stay loudly visible in both). No allowlist, no
   marker interface: in a durable framework, the fate of a failed effect is a folded
   fact, not an escaped stack trace. `Error`s still propagate — the JVM's problems
   are not conversation fate.
2. **The reducer is untouched.** The mixed flush is correct grammar and good
   semantics (interjected words arrive beside the results they waited on, exactly as
   Anthropic's wire natively expresses). The false axiom lives in the mappings, and
   in `OpenAiRequests`' javadoc ("never both" — delete the claim); make the mappings
   total over the grammar instead of narrowing the grammar to the mappings.
3. **OpenAI partitions.** A USER message's blocks split: every `ToolResultBlock`
   becomes its own `tool`-role message (they must directly follow the assistant's
   `tool_calls` message); remaining blocks, if any, become ONE `user`-role message
   after them, built by the existing `toUserMessageParam`. Pure-text and pure-results
   messages behave exactly as today.
4. **Gemini goes per-block.** Its user `Content` natively holds `functionResponse`
   parts and text parts together — map each block to its part kind in one Content,
   Anthropic-style. No split needed.

## 3. Consequence the owner accepts

Today's accident let "try again" silently resume a zombie turn. After Bug A's fix,
a failed call closes the turn as FAILED; the next tell opens a fresh turn through
the documented door (`openTurn` clears the failure, full history still visible to
the model). The interjection door (`told`) remains for genuinely open turns —
tools still running, parked calls — which is what it was for.

## 4. Testing

House rules. Core: a scripted provider whose first call throws a plain
`RuntimeException` → conversation status FAILED with the exception-derived reason
(offline regression for the live trace); a subsequent tell opens a fresh turn and
completes normally; `ContextOverflowException` path unchanged. Mappings: a USER
message of `[ToolResultBlock, TextBlock]` → OpenAI: tool message(s) then one user
message, in that order; Gemini: one user Content holding functionResponse + text
parts; pure-text and pure-results messages pinned unchanged in both. The mixed
message in tests is built directly (`Message.user(...)`/`Message.toolResults(...)`)
— no need to manufacture the zombie state once Bug A is closed.

## 4.5 Final-review amendments (ratified rulings)

- **Failure domains, not exception classes.** The no-allowlist rule separates
  *provider-domain* failures (the call, stream iteration, hydration's own provider
  calls — all fold as `ModelCallFailed`) from *caller-domain* failures (a
  `TurnObserver` that throws during narration — propagates, per `TurnObserver`'s
  published contract). The distinction is drawn by call site, not by exception
  class: an internal wrapper around observer invocations is the sanctioned
  mechanism and is not the forbidden allowlist.
- **Consecutive USER messages are legal — grammar and wire.** Recovery after a
  failed call leaves `user(A), user(B)` in history; plan/notebook enrichment has
  produced the same shape on every planned Anthropic turn since the feature
  shipped, live-proven in Scout. The CHANGELOG's old "the wire forbids consecutive
  user messages" rationale describes a constraint today's APIs no longer impose.
  The shape is pinned by test, not "fixed."
- **Hydration failures fold too.** `execute`'s recall path (e.g. a summarizing
  hydrator's compaction call) is provider-domain: its `RuntimeException`s fold,
  with the `ContextOverflowException` arm keeping its distinct reason first.
- **Arm parity.** The overflow arm marks the observation errored and logs at
  ERROR exactly like the general arm; its distinct reason text is pinned by test.

## 5. Out of scope

- Reducer flush shape (`toolFinished`/`halted`) — explicitly kept as is.
- Retry policy (`RetryingModelProvider` remains the decorator for retryable
  failures; the executor's fold is about fate, not retries).
- The investigator's option 2 (told in its own trailing message) — rejected: churns
  transcript shape and message-count semantics for no wire need.
