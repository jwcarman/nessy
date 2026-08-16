/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.nessy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.UnknownParkTokenException;
import org.jwcarman.nessy.api.WrongAgentException;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationSnapshot;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.InboxEntry;
import org.jwcarman.nessy.api.conversation.ParkedCall;
import org.jwcarman.nessy.api.event.ListenerRegistry;
import org.jwcarman.nessy.api.event.ToolProgress;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.internal.ConversationLoop;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.conversation.Parks;
import org.jwcarman.nessy.spi.conversation.Parks.Park;
import org.jwcarman.nessy.spi.memory.Memory;

/**
 * A configured agent: a reusable factory of conversations, with the full machinery one call away.
 *
 * <p>There is no agent-wide dynamic subscription any more (design §17): a listener that must watch
 * every conversation this agent ever runs is declared once, at build time, via {@code
 * AgentConfig#listen}/{@code listenAsync}; the only thing left to attach at runtime is one
 * conversation's own traffic, through {@link Conversation#events()}.
 *
 * @param <I> the input vocabulary a {@code tell} to one of this agent's conversations may carry
 */
public final class Agent<I> {

  private static final String TOKEN_MUST_NOT_BE_NULL = "token must not be null";

  private final String name;
  private final ConversationLoop loop;
  private final ListenerRegistry events;
  private final ConversationStore store;
  private final Parks parks;
  private final Map<String, Agent<?>> subagents;
  private final Memory memory;
  private final InputRenderer<I> renderer;

  /**
   * The two coordination pieces a subagent's doors need — {@link Parks}, for the ordinary callback
   * doors every agent has, and this agent's own direct children, keyed by name, for {@link
   * #subagent(String)} — bundled together (java:S107: an eighth constructor parameter otherwise).
   * Grandchildren are not carried here: {@link Subagent#subagent(String)} reaches them by asking
   * the child's own {@link Agent#subagent(String)} in turn, so each agent only ever needs to
   * remember its own direct children.
   *
   * <p>{@code Agent<?>}, not {@code Agent<String>}: a typed-door child (design of record 2026-08-16
   * §0.5) is an {@code Agent<T>} for whatever record its own delegation tool carries, not
   * necessarily {@code String}. Every door {@link Subagent} delegates to —
   * approve/deny/resume/snapshot/subagent — is independent of the child's own vocabulary, so the
   * wildcard costs nothing at the handle and buys typed subagents their own agents.
   */
  record Coordination(Parks parks, Map<String, Agent<?>> subagents) {

    Coordination {
      Objects.requireNonNull(parks, "parks must not be null");
      Objects.requireNonNull(subagents, "subagents must not be null");
      subagents = Map.copyOf(subagents);
    }
  }

  Agent(
      String name,
      ConversationLoop loop,
      ListenerRegistry events,
      ConversationStore store,
      Coordination coordination,
      Memory memory,
      InputRenderer<I> renderer) {
    this.name = Objects.requireNonNull(name, "name must not be null");
    this.loop = Objects.requireNonNull(loop, "loop must not be null");
    this.events = Objects.requireNonNull(events, "events must not be null");
    this.store = Objects.requireNonNull(store, "store must not be null");
    Objects.requireNonNull(coordination, "coordination must not be null");
    this.parks = coordination.parks();
    this.subagents = coordination.subagents();
    this.memory = Objects.requireNonNull(memory, "memory must not be null");
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
  }

  /** This agent's required, durable identity (design §3) — the stamp its parks carry. */
  public String name() {
    return name;
  }

  /**
   * This agent's own direct child, named {@code name}, as a narrow {@link Subagent} doors handle
   * (design of record 2026-08-16 §0, ruling 3) — {@code approve}/{@code deny}/{@code resume}/{@code
   * snapshot} against the child, and further traversal via {@link Subagent#subagent(String)} for a
   * grandchild. Only this agent's own directly-declared children are reachable here; a deeper
   * descendant is reached by chaining, one door at a time, exactly matching the lexical nesting
   * {@link AgentConfig#subagent(SubagentCustomizer)} and {@link
   * SubagentConfig#subagent(SubagentCustomizer)} built the tree with — either the degenerate {@code
   * String} door or the typed door (design of record 2026-08-16 §0.5); the handle is the same
   * either way.
   *
   * @throws IllegalArgumentException if this agent has no subagent named {@code name}
   */
  public Subagent subagent(String name) {
    Objects.requireNonNull(name, "name must not be null");
    Agent<?> child = subagents.get(name);
    if (child == null) {
      throw new IllegalArgumentException(
          "agent '" + this.name + "' has no subagent named '" + name + "'");
    }
    return new Subagent(child);
  }

  /** Opens a fresh conversation. */
  public Conversation<I> converse() {
    return new Conversation<>(loop, ConversationId.generate(), events, renderer);
  }

  /**
   * Reopens a stored session. The loop loads its state on the next send — the same lazy load {@link
   * #converse()} defers, just against an existing id instead of a fresh one.
   */
  public Conversation<I> conversation(ConversationId conversationId) {
    return new Conversation<>(loop, conversationId, events, renderer);
  }

  /**
   * Answers a parked call, watched by no one ({@link TurnObserver#noop()}).
   *
   * @throws UnknownParkTokenException if {@code token} names no wait this registry has ever seen
   * @throws WrongAgentException if {@code token} names a wait minted by a different agent
   * @see #resume(ParkToken, ToolResolution, TurnObserver)
   */
  public RunOutcome resume(ParkToken token, ToolResolution resolution) {
    return resume(token, resolution, TurnObserver.noop());
  }

  /**
   * Answers a parked call. Pass {@code token} — naming the wait some prior turn is durably patient
   * for — and {@code resolution}, the answer it has been waiting on; an optional {@code observer}
   * watches the drive that follows, the same re-entrant act {@link #resume} shares with {@code
   * tell}: the inbox absorbs the answer, the status pointer says what happens next. Unknown tokens
   * are rejected loud rather than silently dropped, and a token minted by a different agent is
   * refused, after verifying the park's stamp, before anything is appended or driven (design §3,
   * §5).
   *
   * <p>The registry entry survives resolution (design §5) — it is the durable record that this
   * token once named this wait, not a single-use claim — so a redelivered resume (every real
   * transport is at-least-once) translates the token again and appends another {@code Resolved}
   * entry. Once the call has fully settled — folded to {@code COMPLETE}/{@code FAILED}, or already
   * drained by an earlier delivery of this exact resolution — the fold's own
   * is-this-call-still-outstanding check drains the redelivery quietly rather than replaying the
   * call: the drive simply reads whatever the first delivery already produced. That quiet-drain
   * promise is narrower since design §4's repark fix, though: a call that reparked for its own
   * execution wait (permission, then work) is still outstanding, under a NEW token — a redelivered
   * resume of the original, already-answered token routes straight back through the parked
   * executor's own {@code resume} and re-invokes the tool, exactly as a fresh call would. That is
   * safe only for a tool that makes itself idempotent (the subagent tool's own snapshot
   * short-circuit, for instance) — not a general promise this door makes — and the loop's own
   * one-outstanding-park guard is what catches a non-idempotent tool minting a second, orphaning
   * token from that replay rather than letting it silently succeed. Either way, appending always
   * succeeds.
   *
   * <p>That quiet-drain protection is serial, not concurrent: it is the fold picking a winner among
   * entries already appended, so it only shields a resume that arrives after an earlier one has
   * finished folding. Two deliveries of the same token driven concurrently can both observe the
   * call as still outstanding and both invoke the tool before the fence settles on which fold wins
   * — the same at-least-once exposure {@link org.jwcarman.nessy.api.tool.Tool} already documents: a
   * tool that cannot be safely re-run makes itself idempotent, or parks and lets its remote side
   * deduplicate by token.
   *
   * @throws UnknownParkTokenException if {@code token} names no wait this registry has ever seen
   * @throws WrongAgentException if {@code token} names a wait minted by a different agent
   */
  public RunOutcome resume(ParkToken token, ToolResolution resolution, TurnObserver observer) {
    Objects.requireNonNull(token, TOKEN_MUST_NOT_BE_NULL);
    Objects.requireNonNull(resolution, "resolution must not be null");
    Objects.requireNonNull(observer, "observer must not be null");
    Park park = verified(token);
    store.append(park.conversationId(), InboxEntry.resolved(park.call().id(), resolution));
    return loop.drive(park.conversationId(), observer);
  }

  /**
   * Approves a parked call, watched by no one ({@link TurnObserver#noop()}).
   *
   * @throws UnknownParkTokenException if {@code token} names no wait this registry has ever seen
   * @throws WrongAgentException if {@code token} names a wait minted by a different agent
   * @see #approve(ParkToken, TurnObserver)
   */
  public RunOutcome approve(ParkToken token) {
    return approve(token, TurnObserver.noop());
  }

  /**
   * Sugar over {@link #resume(ParkToken, ToolResolution, TurnObserver)} for the common HITL
   * verdict: an unconditional {@link Decision#allow()}. No logic of its own.
   *
   * @throws UnknownParkTokenException if {@code token} names no wait this registry has ever seen
   * @throws WrongAgentException if {@code token} names a wait minted by a different agent
   */
  public RunOutcome approve(ParkToken token, TurnObserver observer) {
    return resume(token, new ToolResolution.Decided(Decision.allow()), observer);
  }

  /**
   * Denies a parked call, watched by no one ({@link TurnObserver#noop()}).
   *
   * @throws UnknownParkTokenException if {@code token} names no wait this registry has ever seen
   * @throws WrongAgentException if {@code token} names a wait minted by a different agent
   * @see #deny(ParkToken, String, TurnObserver)
   */
  public RunOutcome deny(ParkToken token, String reason) {
    return deny(token, reason, TurnObserver.noop());
  }

  /**
   * Sugar over {@link #resume(ParkToken, ToolResolution, TurnObserver)} for the common HITL
   * verdict: a {@link Decision.Deny} carrying {@code reason} back to the model. No logic of its
   * own.
   *
   * @throws UnknownParkTokenException if {@code token} names no wait this registry has ever seen
   * @throws WrongAgentException if {@code token} names a wait minted by a different agent
   */
  public RunOutcome deny(ParkToken token, String reason, TurnObserver observer) {
    Objects.requireNonNull(reason, "reason must not be null");
    return resume(token, new ToolResolution.Decided(new Decision.Deny(reason)), observer);
  }

  /**
   * Reads a park without consuming it — the same {@link Parks#find} read {@link #progress} narrates
   * against, exposed directly so a caller can inspect what a token is waiting on before deciding
   * how to {@link #resume} it. Unlike {@link #resume}, an unknown token is not an error: {@link
   * Optional#empty()} says the wait is not there to read, exactly as {@link #progress} treats it. A
   * token minted by a different agent is still refused loud, the same as every other door (design
   * §2: one front door, no exceptions) — peeking never leaks another agent's park.
   *
   * @throws WrongAgentException if {@code token} names a wait minted by a different agent
   */
  public Optional<ParkedCall> peek(ParkToken token) {
    Objects.requireNonNull(token, TOKEN_MUST_NOT_BE_NULL);
    Optional<Park> found = parks.find(token);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    Park park = verified(found.get());
    return Optional.of(new ParkedCall(park.token(), park.call()));
  }

  /**
   * The remote signal channel: a tool still running out in the world reports {@code message}
   * against the wait it parked under. {@code token} is only ever peeked, via {@link Parks#find},
   * never consumed — this is narration, not a resolution, and the wait itself remains exactly as
   * resumable afterward as it was before. An unknown token is not an error, nor is a token the
   * registry still recognizes but whose call the conversation's own state no longer lists as
   * outstanding (design §5: registry entries survive resolution, so a settled wait's token stays
   * findable forever) — either way the signal simply has nowhere left to land, so it is dropped and
   * {@code false} says so. A token minted by a different agent is refused loud rather than treated
   * as merely unknown (design §3), after the same park-stamp check every door runs. A live token
   * emits {@link ToolProgress} on this agent's own {@link #events} — the same {@link
   * ListenerRegistry} the in-process tee narrates on — reaching harness-seeded and agent-declared
   * listeners alike, the identical audience the tee reaches, carrying the park's own conversation
   * and call id, and returns {@code true}.
   *
   * @throws WrongAgentException if {@code token} names a wait minted by a different agent
   */
  public boolean progress(ParkToken token, String message) {
    Objects.requireNonNull(token, TOKEN_MUST_NOT_BE_NULL);
    Objects.requireNonNull(message, "message must not be null");
    Optional<Park> found = parks.find(token);
    if (found.isEmpty()) {
      return false;
    }
    Park park = verified(found.get());
    boolean stillOutstanding =
        store
            .load(park.conversationId())
            .map(
                loaded ->
                    loaded.state().parkedCalls().stream()
                        .anyMatch(call -> call.id().equals(park.call().id())))
            .orElse(false);
    if (!stillOutstanding) {
      return false;
    }
    events.emit(new ToolProgress(park.conversationId(), park.call().id(), message));
    return true;
  }

  /**
   * The ownership check every callback door runs before appending or driving anything (design §2,
   * §3, §5): {@code token} translates fine, but a {@link Park#agentName()} that names some other
   * agent means this delivery is refused whole, loud, and first — nothing about the conversation
   * changes. {@link UnknownParkTokenException} covers the token-not-found case; this covers the
   * found-but-not-mine case.
   *
   * @throws WrongAgentException if {@code park} was minted by an agent other than this one
   */
  private Park verified(Park park) {
    if (!park.agentName().equals(name)) {
      throw new WrongAgentException(park.token(), park.agentName(), name);
    }
    return park;
  }

  /**
   * Looks the park up by {@code token} first, then verifies its stamp the same way.
   *
   * @throws UnknownParkTokenException if {@code token} names no wait this registry has ever seen
   * @throws WrongAgentException if the found park was minted by an agent other than this one
   */
  private Park verified(ParkToken token) {
    return verified(parks.find(token).orElseThrow(() -> new UnknownParkTokenException(token)));
  }

  /**
   * The debugging affordance: exactly what a conversational call made against {@code id} right now
   * would see — the same {@link Memory#recall} the loop's own {@code ModelCallExecutor} consults on
   * every send, since that recall is the sole context-assembly seam left after the cutover (design
   * §17). Truthful without a model call, because recall is deterministic over what has already been
   * told.
   *
   * <p>{@code contextFor} throws because an unknown id under a debugger is a bug; {@link #snapshot}
   * is total because a browser-minted fresh id is a normal page rebuild. {@code id} itself is
   * validated the same way {@link #snapshot}'s is — a matched pair, not a divergence.
   *
   * @throws IllegalArgumentException if no conversation {@code id} is stored
   */
  public Context contextFor(ConversationId id) {
    Objects.requireNonNull(id, "id must not be null");
    store.load(id).orElseThrow(() -> new IllegalArgumentException("unknown conversation: " + id));
    return memory.recall(id);
  }

  /**
   * The total page-rebuild read: everything a fresh page load needs to redraw one conversation,
   * whether or not it has ever been stored. {@code snapshot} is total because a browser-minted
   * fresh id is a normal page rebuild; {@link #contextFor} throws because an unknown id under a
   * debugger is a bug.
   *
   * <p>One {@link ConversationStore#load} plus, when a stored conversation is found, one {@link
   * Memory#recall} — the same recall {@link #contextFor} and the loop's own {@code
   * ModelCallExecutor} consult. The approval-card view (design §7) composes the state's own
   * outstanding call ids against {@link Parks#forConversation}: the state no longer carries tokens,
   * so the cards are {@link Parks} registry entries filtered down to whichever calls {@link
   * org.jwcarman.nessy.api.conversation.ConversationState#parkedCalls()} still names outstanding,
   * rendered as the same {@code (token, call)} pairs this snapshot always handed back — in {@link
   * org.jwcarman.nessy.api.conversation.ConversationState#parkedCalls()}'s own order (the order the
   * calls were parked in), not whatever order the registry happens to iterate.
   */
  public ConversationSnapshot snapshot(ConversationId id) {
    Objects.requireNonNull(id, "id must not be null");
    return store
        .load(id)
        .map(
            loaded ->
                new ConversationSnapshot(
                    loaded.state().status(), cards(id, loaded), memory.recall(id)))
        .orElseGet(
            () -> new ConversationSnapshot(ConversationStatus.IDLE, List.of(), Context.empty()));
  }

  /**
   * A {@code StaleStateException}-retried park, or a call that has parked more than once (design of
   * record 2026-08-16 §4: an approval wait followed by its own execution wait), can legitimately
   * register more than one token for the same call id — every one of them resolves that same
   * outstanding call, so the collision must not crash the page rebuild. {@code toMap}'s merge
   * function picks a winner with no ordering contract over {@link Parks#forConversation}'s own
   * return (it is a plain {@code List}, not sorted by registration time), so for a reparked call
   * the token this method reports is <strong>unspecified</strong> — any of that call's outstanding
   * tokens may come back. That is harmless for {@code approve}/{@code deny} against the current
   * wait: every door verifies the token against {@link Parks#find} at the moment it is used, so
   * whichever token this method happens to report still routes to the same call when presented
   * back. It is not harmless in every sense, though: a reported token that turns out to be the
   * call's older, already-answered wait (its approval wait, say, rather than its live execution
   * wait) re-invokes the parked executor's own {@code resume} exactly as a fresh resolution would
   * (design §4's repark fix narrows the quiet-drain promise {@link #resume}'s own javadoc
   * documents), rather than doing nothing new — safe for an idempotent tool, a live re-run for
   * anything else. A caller building a UI atop this card should not assume presenting it back is
   * always a no-op replay.
   */
  private List<ParkedCall> cards(ConversationId id, ConversationStore.Loaded loaded) {
    Map<String, Park> byCallId =
        parks.forConversation(id).stream()
            .collect(
                Collectors.toMap(
                    park -> park.call().id(), Function.identity(), (first, second) -> first));
    return loaded.state().parkedCalls().stream()
        .map(ToolCall::id)
        .map(byCallId::get)
        .filter(Objects::nonNull)
        .map(park -> new ParkedCall(park.token(), park.call()))
        .toList();
  }
}
