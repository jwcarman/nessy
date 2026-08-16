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
package org.jwcarman.nessy.spi.subagent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationSettled;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationSnapshot;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.conversation.Parks;

/**
 * Turns an {@link Agent} into an ordinary {@link Tool}: a subagent call is a tool call whose work
 * is another agent's conversation.
 *
 * <p>{@link #subagent} builds the tool the parent's model calls; {@link #completions} builds the
 * listener that wakes the parent once the child settles. The two halves are linked by {@link
 * SubagentLinks}, which remembers only which parent {@link ParkToken} a child conversation answers
 * — the parent agent's own name is never duplicated there, because {@link
 * org.jwcarman.nessy.spi.conversation.Parks.Park#agentName()} already carries it (spec §5
 * amendment): the loop stamps it the moment the parent's own park is registered, so there is
 * exactly one place that fact can go stale.
 */
public final class AgentTools {

  private AgentTools() {}

  /** What the model hands a subagent tool call: the task for the child to carry out. */
  public record Delegation(String task) {

    public Delegation {
      if (task == null || task.isBlank()) {
        throw new IllegalArgumentException("task must not be blank");
      }
    }
  }

  /**
   * {@link #subagent(Agent, String, SubagentLinks)} with no {@link SubagentLinks} store: fine for a
   * child whose own tools never park, but the moment the child does park, {@link Tool#execute}
   * throws {@link IllegalStateException} naming the missing store — there is nowhere to remember
   * the parent's own park token.
   *
   * <p>That throw never reaches the application, though: the harness's own tool-call executor
   * catches whatever a tool throws and turns it into an ordinary {@link ToolResult#error}, so the
   * model simply sees a failed call — and the child conversation is left parked on a token nobody
   * holds, unreachable forever. The {@link SubagentLinks}-carrying overload is the only durable
   * path; reach for this one only when the child is known never to park.
   */
  public static Tool<Delegation> subagent(Agent<String> child, String description) {
    return subagent(child, description, null);
  }

  /**
   * Turns {@code child} into a tool: {@link Tool#name()} is {@code child.name()}, {@link
   * Tool#description()} is {@code description}, and one call runs one turn of a dedicated child
   * conversation (see {@link Tool#execute} for the parking recipe {@code links} makes possible).
   */
  public static Tool<Delegation> subagent(
      Agent<String> child, String description, SubagentLinks links) {
    Objects.requireNonNull(child, "child must not be null");
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException("description must not be blank");
    }
    return new SubagentTool(child, description, links);
  }

  /**
   * The wake-up consumer: registered <strong>synchronously</strong> — {@code
   * harnessBuilder.listen(ConversationSettled.class, AgentTools.completions(links, parks,
   * router))}, never {@code listenAsync}. A subagent's settlement is exactly the kind of fact an
   * at-least-once transport must be able to retry: if this consumer swallowed (or merely logged) a
   * failed {@link Agent#resume}, the parent would stay parked forever with nobody left to nudge it.
   * Registered sync, a throw here propagates to whatever redrove the child's settlement — the same
   * caller sees the failure and, being at-least-once, redelivers it.
   *
   * <p>The routing name is read off {@link Parks.Park#agentName()}, not off {@link SubagentLinks}
   * (spec §5 amendment) — {@code links} answers only "which parent token", never "which agent
   * minted it". Three cases, in order:
   *
   * <ul>
   *   <li>{@code links.find} is empty — three cases, not two, and only two of them are the harmless
   *       idempotency this makes possible: this child never delegated a park (it settled in one
   *       shot with nothing to wake); a prior delivery already {@link SubagentLinks#forget} it (a
   *       genuine duplicate settlement); or the mirror race to the one documented below — the
   *       child's settlement won the gap between {@link Tool#execute}'s own {@code
   *       child.conversation(childId).tell(...)} returning {@link RunOutcome.Parked} and that same
   *       {@code execute}'s later {@link SubagentLinks#save}, so this delivery finds nothing on
   *       file yet and takes this same silent-no-op arm. That third case is <strong>not</strong>
   *       idempotent no-op: {@code execute} still saves the link and parks the parent a moment
   *       later, on a child that has already settled — nothing will ever drive that child again
   *       (the settlement fact only fires on a drive), so the parent stays parked until a retry or
   *       manual wake finds it. The window is narrow and undefended on purpose (the honest inverse
   *       ordering — save the link before telling — trades this for a worse failure on the
   *       sync-completion path, see {@link Tool#execute}), not unnoticed.
   *   <li>{@code links.find} is present but {@code parks.find} is empty — this is
   *       <strong>not</strong> "nobody is waiting any more": {@link Parks} never deletes an entry
   *       once registered (see {@link Parks}'s own javadoc), so an absent park can only mean the
   *       parent's own park has not been registered <em>yet</em> — the narrow window between this
   *       {@code execute}'s own {@link SubagentLinks#save} and the parent loop's later {@code
   *       Parks.park} call landing. Forgetting the link here would be a lost wakeup: the child has
   *       already settled, the park is about to register moments later, and nothing will ever wake
   *       it again. This throws {@link IllegalStateException} instead, naming the child and the
   *       not-yet-registered token — the settlement fires inside the child driver's own synchronous
   *       drive, so the throw fails that delivery and the at-least-once transport (the same
   *       reasoning this method's own sync-registration requirement rests on) retries once the
   *       window has closed. A genuine duplicate settlement — the same child settling twice — never
   *       reaches this branch: the first successful wake already forgot the link, so the second
   *       delivery drains via the empty-{@code links.find} case above.
   *   <li>Both present — the settlement resolves the parent's own park: {@link
   *       ConversationStatus#COMPLETE} resumes with {@link ToolResolution.Completed} carrying
   *       {@link ToolResult#ok}; {@link ConversationStatus#FAILED} carries {@link
   *       ToolResult#error}. The link is forgotten only after {@code resume} returns without
   *       throwing — a resume that throws (an unknown token, a {@link
   *       org.jwcarman.nessy.api.WrongAgentException}) leaves the link in place, undocumented and
   *       unwrapped, for whatever redelivery follows.
   * </ul>
   */
  public static Consumer<ConversationSettled> completions(
      SubagentLinks links, Parks parks, CallbackRouter router) {
    Objects.requireNonNull(links, "links must not be null");
    Objects.requireNonNull(parks, "parks must not be null");
    Objects.requireNonNull(router, "router must not be null");
    return event -> {
      ConversationId childId = event.conversationId();
      Optional<ParkToken> parentToken = links.find(childId);
      if (parentToken.isEmpty()) {
        return;
      }
      ParkToken token = parentToken.get();
      Optional<Parks.Park> park = parks.find(token);
      if (park.isEmpty()) {
        throw new IllegalStateException(
            "child "
                + childId.value()
                + " settled before its parent's own park ("
                + token.value()
                + ") was registered — Parks entries are never deleted, so an absent park can only"
                + " mean this settlement arrived inside the narrow window between"
                + " SubagentLinks.save and the parent loop registering its own park; failing this"
                + " delivery lets the at-least-once transport retry once that window has closed");
      }
      ToolResult result =
          event.status() == ConversationStatus.COMPLETE
              ? ToolResult.ok(event.finalAssistantText())
              : ToolResult.error(event.failureReason());
      router.resume(park.get().agentName(), token, new ToolResolution.Completed(result));
      links.forget(childId);
    };
  }

  /**
   * One call of {@link #subagent}: one turn of a dedicated, stable child conversation (see {@link
   * #execute}), rendered so the model sees an ordinary tool.
   */
  private static final class SubagentTool implements Tool<Delegation> {

    private final Agent<String> child;
    private final String description;
    private final SubagentLinks links;

    SubagentTool(Agent<String> child, String description, SubagentLinks links) {
      this.child = child;
      this.description = description;
      this.links = links;
    }

    @Override
    public String name() {
      return child.name();
    }

    @Override
    public String description() {
      return description;
    }

    @Override
    public Class<Delegation> inputType() {
      return Delegation.class;
    }

    @Override
    public String describe(Delegation input) {
      return input.task();
    }

    /**
     * Drives (at most) one turn of a child conversation stably keyed to this call — {@code
     * parentConversationId/toolCallId} — so a redelivered call (every real transport is
     * at-least-once) lands on the same child conversation rather than spawning a sibling.
     *
     * <p><strong>True short-circuit idempotency, not the transcript's own no-stutter rule.</strong>
     * A redelivered {@code execute} would otherwise re-{@code tell} an already-settled or
     * already-parked child — this harness has no ordinary at-least-once tool semantics that absorb
     * that on their own (a {@code tell} against a completed conversation drives a fresh turn; a
     * {@code tell} against a parked one queues a second, later-folding {@code Told}), so this door
     * inspects {@link Agent#snapshot} <em>before</em> telling anything, and only a genuinely
     * fresh/idle child is told at all:
     *
     * <ul>
     *   <li>{@link ConversationStatus#COMPLETE} — answered without telling, from the child's last
     *       assistant message, read back via {@link Agent#contextFor} the same way {@code
     *       ConversationLoop}'s own settlement-fact fallback does.
     *   <li>{@link ConversationStatus#FAILED} — answered without telling, as {@link
     *       ToolResult#error}. {@link Agent#snapshot}/{@link Agent#contextFor} do not expose {@link
     *       ConversationState#failureReason()} for an already-stored conversation (that field lives
     *       only on a live {@link RunOutcome}'s state), so a replayed FAILED answer carries a
     *       generic "already failed" message rather than fabricating the original reason — a fresh,
     *       first-time FAILED settlement (below, off the just-driven {@code RunOutcome}) still
     *       carries the real one.
     *   <li>{@link ConversationStatus#PARKED} — answered without telling, returning the parent
     *       token already on file in {@code links} rather than minting a fresh one (minting again
     *       here would orphan the earlier token's park entry and reopen the {@link #completions}
     *       race window on every replay). {@code links} is this delegation's own bookkeeping, so a
     *       parked child with no matching link is a bug, not a recoverable state: {@link
     *       IllegalStateException}.
     *   <li>Anything else (no conversation stored yet, or a status a redelivery should never
     *       actually observe outside a genuine crash-replay) — a fresh {@code tell}, narrated by a
     *       progress relay: a {@link TurnObserver} passed to the child's own {@code tell} calls
     *       {@link ToolContext#progress} on every {@link TurnEvent.ToolCallRequested} the child's
     *       turn narrates — an activity ping (documented approximation, spec §9 wants per-turn),
     *       not a per-turn summary, so a long delegation chain never looks frozen even though it
     *       says less than the child is actually doing. Its own outcome maps the same way: {@link
     *       RunOutcome.Completed} through {@link #settled}, {@link RunOutcome.Parked} through
     *       {@link #freshPark}, which mints the parent {@link ParkToken} the same way {@code
     *       RequestFulfillmentTool} (order-desk) and {@code RequestFieldCrewTool} (dispatcher) do.
     * </ul>
     */
    @Override
    public Awaited<ToolResult> execute(Delegation input, ToolContext context) {
      ConversationId childId =
          new ConversationId(context.conversationId().value() + "/" + context.call().id());
      ConversationSnapshot snapshot = child.snapshot(childId);
      return switch (snapshot.status()) {
        case COMPLETE -> Awaited.ready(ToolResult.ok(finalAssistantText(snapshot.context())));
        case FAILED ->
            Awaited.ready(
                ToolResult.error(
                    "subagent '" + child.name() + "' already failed; this replay was not re-run"));
        case PARKED -> existingPark(childId);
        default -> tell(childId, input, context);
      };
    }

    private Awaited<ToolResult> tell(
        ConversationId childId, Delegation input, ToolContext context) {
      TurnObserver progressRelay =
          event -> {
            if (event instanceof TurnEvent.ToolCallRequested(ToolCall call)) {
              context.progress(child.name() + ": " + call.name());
            }
          };
      RunOutcome outcome = child.conversation(childId).tell(input.task(), progressRelay);
      return switch (outcome) {
        case RunOutcome.Parked _ -> freshPark(childId);
        case RunOutcome.Completed(ConversationState state) -> settled(childId, state);
      };
    }

    private Awaited<ToolResult> settled(ConversationId childId, ConversationState state) {
      return switch (state.status()) {
        case COMPLETE ->
            Awaited.ready(ToolResult.ok(finalAssistantText(child.contextFor(childId))));
        case FAILED -> Awaited.ready(ToolResult.error(state.failureReason()));
        default ->
            throw new IllegalStateException(
                "a completed subagent run carried an unexpected status: " + state.status());
      };
    }

    private Awaited<ToolResult> freshPark(ConversationId childId) {
      requireLinks();
      ParkToken parentToken = ParkToken.generate();
      links.save(childId, parentToken);
      return Awaited.parked(parentToken);
    }

    private Awaited<ToolResult> existingPark(ConversationId childId) {
      requireLinks();
      ParkToken parentToken =
          links
              .find(childId)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "child conversation "
                              + childId.value()
                              + " is parked, but SubagentLinks has no parent token on file for it"
                              + " — the delegation's own bookkeeping is missing an entry it should"
                              + " hold"));
      return Awaited.parked(parentToken);
    }

    private void requireLinks() {
      if (links == null) {
        throw new IllegalStateException(
            "the child parked, but no SubagentLinks store was configured for '"
                + child.name()
                + "' — pass one to AgentTools.subagent(child, description, links) so the parent's"
                + " own park can be correlated back to the child's eventual settlement");
      }
    }

    /**
     * The last {@link Role#ASSISTANT} message's text blocks, concatenated in order — the durable
     * transcript, the same fallback source {@code ConversationLoop.publishSettlement} consults when
     * it has no attempt-local message of its own. The empty string when the child said nothing.
     */
    private String finalAssistantText(Context context) {
      List<Message> messages = context.messages();
      for (int i = messages.size() - 1; i >= 0; i--) {
        Message message = messages.get(i);
        if (message.role() == Role.ASSISTANT) {
          return joinedText(message);
        }
      }
      return "";
    }

    private static String joinedText(Message message) {
      StringBuilder text = new StringBuilder();
      for (ContentBlock block : message.content()) {
        if (block instanceof TextBlock(String blockText)) {
          text.append(blockText);
        }
      }
      return text.toString();
    }
  }
}
