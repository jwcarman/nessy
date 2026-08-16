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
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.Tool;
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
   *   <li>{@code links.find} is empty — either this child never delegated a park (it settled in one
   *       shot with nothing to wake), or a prior delivery already {@link SubagentLinks#forget} it.
   *       Either way, a silent no-op — this is what makes a duplicate settlement idempotent.
   *   <li>{@code links.find} is present but {@code parks.find} is empty — the parent token is
   *       known, but nobody is waiting on it any longer (its wait already resolved some other way).
   *       The link is forgotten and nothing is resumed.
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
        links.forget(childId);
        return;
      }
      ToolResult result =
          event.status() == ConversationStatus.COMPLETE
              ? ToolResult.ok(event.finalAssistantText())
              : ToolResult.error(event.failureReason());
      router.route(park.get().agentName()).resume(token, new ToolResolution.Completed(result));
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
     * Drives one turn of a child conversation stably keyed to this call — {@code
     * parentConversationId/toolCallId} — so a redelivered call (every real transport is
     * at-least-once) lands on the same child conversation rather than spawning a sibling: the child
     * folds the redelivered {@code tell} once, no stutter.
     *
     * <p>Progress relay (documented approximation, spec §9 wants per-turn): a {@link TurnObserver}
     * passed to the child's own {@code tell} calls {@link ToolContext#progress} on every {@link
     * TurnEvent.ToolCallRequested} the child's turn narrates — an activity ping, not a per-turn
     * summary, so a long delegation chain never looks frozen even though it says less than the
     * child is actually doing.
     *
     * <p>{@link ConversationStatus#COMPLETE} answers with the child's last assistant message, read
     * back via {@link Agent#contextFor} the same way {@code ConversationLoop}'s own settlement-fact
     * fallback does (durable transcript, not attempt-local state — this door has no attempt-local
     * message to fast-path from). {@link ConversationStatus#FAILED} answers with {@link
     * ConversationState#failureReason()} directly. A park mints a fresh parent {@link ParkToken}
     * the same way {@code RequestFulfillmentTool} (order-desk) and {@code RequestFieldCrewTool}
     * (dispatcher) do, saves the correlation, and returns it — requiring {@code links} to exist,
     * since without one there is nowhere to remember which parent token the child's eventual
     * settlement must resume.
     */
    @Override
    public Awaited<ToolResult> execute(Delegation input, ToolContext context) {
      ConversationId childId =
          new ConversationId(context.conversationId().value() + "/" + context.call().id());
      TurnObserver progressRelay =
          event -> {
            if (event instanceof TurnEvent.ToolCallRequested requested) {
              context.progress(child.name() + ": " + requested.call().name());
            }
          };
      RunOutcome outcome = child.conversation(childId).tell(input.task(), progressRelay);
      return switch (outcome) {
        case RunOutcome.Parked _ -> park(childId);
        case RunOutcome.Completed completed -> settled(childId, completed.state());
      };
    }

    private Awaited<ToolResult> settled(ConversationId childId, ConversationState state) {
      return switch (state.status()) {
        case COMPLETE -> Awaited.ready(ToolResult.ok(finalAssistantText(childId)));
        case FAILED -> Awaited.ready(ToolResult.error(state.failureReason()));
        default ->
            throw new IllegalStateException(
                "a completed subagent run carried an unexpected status: " + state.status());
      };
    }

    private Awaited<ToolResult> park(ConversationId childId) {
      if (links == null) {
        throw new IllegalStateException(
            "the child parked, but no SubagentLinks store was configured for '"
                + child.name()
                + "' — pass one to AgentTools.subagent(child, description, links) so the parent's"
                + " own park can be correlated back to the child's eventual settlement");
      }
      ParkToken parentToken = ParkToken.generate();
      links.save(childId, parentToken);
      return Awaited.parked(parentToken);
    }

    /**
     * The last {@link Role#ASSISTANT} message's text blocks, concatenated in order — the durable
     * transcript read via {@link Agent#contextFor}, the same fallback source {@code
     * ConversationLoop.publishSettlement} consults when it has no attempt-local message of its own.
     * The empty string when the child said nothing.
     */
    private String finalAssistantText(ConversationId childId) {
      List<Message> messages = child.contextFor(childId).messages();
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
