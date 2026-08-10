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
package org.jwcarman.nessy.spi.context;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.event.EventHub;
import org.jwcarman.nessy.api.event.RecallFailed;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.session.SessionState;
import org.jwcarman.nessy.spi.memory.Memory;

/**
 * The Contextualize phase (§6.1), Maven-style: bindings declared once, at build time, in reviewable
 * code — never registered at runtime through the hub. Declaration order is execution order; the
 * same ledger and the same bindings always produce the same {@link Context}.
 *
 * <pre>{@code
 * harness.agent(SupportInput.class)
 *     .context(pipeline -> pipeline
 *         .recall(graphMemory)                 // RECALL: 0..n contributors
 *         .recall(userPreferences)
 *         .shape(Shape.elidingToolResults(2))  // SHAPE: 0..n transforms, declaration order
 *         .placement(Placement.MEMORIES_FIRST)) // where RECALL contributions land
 * }</pre>
 *
 * <p>Concrete machinery, not an extension seam — nothing here is meant to be implemented by SPI
 * consumers, only constructed and called. An agent has exactly one pipeline instance, constructed
 * once at {@code AgentBuilder.build()} time, and shared by every consumer that needs to answer
 * "what would this call see" — {@code InProcessEngine#requestFor} at every conversational request,
 * and {@code Agent.contextFor} on demand. Sharing the instance (rather than each consumer
 * re-deriving the same choreography) is what keeps the two answers from ever drifting apart.
 *
 * <p>{@link #assemble(SessionState)} runs the pipeline in three stages:
 *
 * <ol>
 *   <li><b>Shape</b>: mints {@code Context.of(state.messages())} and applies every declared {@link
 *       Shape} in declaration order. A throwing shape propagates — shapes are pure and total by
 *       contract, so a throw is the application's own bug, not a runtime condition to absorb.
 *   <li><b>Recall</b>: runs each declared {@link Memory} contributor, in declaration order, under
 *       its own {@code nessy.memory.recall} observation. Each contributor is independently
 *       best-effort: a thrown exception, or a contribution that would break {@link Context}'s
 *       tool-pairing invariant when concatenated onto the contributions accepted so far, marks that
 *       contributor's observation with the error, emits {@link RecallFailed}, and costs only that
 *       contributor's own contribution — every other contributor still runs. Zero declared
 *       contributors means zero observations and zero hub emissions, identical to the shaped
 *       context alone.
 *   <li><b>Compose</b>: concatenates the accepted recall contributions with the shaped transcript
 *       per {@link Placement}, and mints the result through {@code Context.of} one last time. This
 *       final validation is provably redundant given the two stages above — each recall
 *       contribution was validated as it was accepted, and shaping always yields a valid {@link
 *       Context} by construction (the record's own invariant), so two independently valid,
 *       independently *closed* sequences (neither ending on a dangling {@code tool_use}, neither
 *       opening on a stray {@code tool_result}) can never break pairing at the seam where they
 *       meet. It stays in as a defensive final check documenting that guarantee; if it ever did
 *       throw, that would mean the guarantee above no longer holds, which is a pipeline bug, not a
 *       recall failure — so, like a throwing shape, it is allowed to propagate rather than being
 *       attributed to any one contributor.
 * </ol>
 */
public final class ContextPipeline {

  private static final String RECALL_OBSERVATION_NAME = "nessy.memory.recall";

  private final List<Memory> recalls;
  private final List<Shape> shapes;
  private final Placement placement;
  private final EventHub hub;
  private final ObservationRegistry observations;

  private ContextPipeline(
      List<Memory> recalls,
      List<Shape> shapes,
      Placement placement,
      EventHub hub,
      ObservationRegistry observations) {
    this.recalls = recalls;
    this.shapes = shapes;
    this.placement = placement;
    this.hub = hub;
    this.observations = observations;
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Assembles the {@link Context} one call against {@code state} sees. {@code state} carries both
   * the messages to shape and the session id ({@link SessionState#id()}) that names the session for
   * the {@link RecallFailed} event a failed recall contributor emits.
   */
  public Context assemble(SessionState state) {
    Context shaped = applyShapes(Context.of(state.messages()));
    if (recalls.isEmpty()) {
      return shaped;
    }
    List<Message> recalled = collectRecallContributions(state);
    if (recalled.isEmpty()) {
      return shaped;
    }
    List<Message> composed =
        placement == Placement.MEMORIES_FIRST
            ? concat(recalled, shaped.messages())
            : concat(shaped.messages(), recalled);
    return Context.of(composed);
  }

  private Context applyShapes(Context context) {
    Context shaped = context;
    for (Shape shape : shapes) {
      shaped = shape.apply(shaped);
    }
    return shaped;
  }

  /**
   * Runs every declared recall contributor in order, folding each accepted contribution onto the
   * ones already accepted. Validating the running total after each contributor (rather than only at
   * the very end) is what lets a pair-breaking contribution be attributed to the one contributor
   * that caused it — the previously accepted contributions are known-good, so if folding in the
   * next one breaks {@link Context}'s pairing invariant, the new contributor is the only thing that
   * changed.
   */
  private List<Message> collectRecallContributions(SessionState state) {
    List<Message> accepted = List.of();
    for (Memory memory : recalls) {
      Observation observation =
          Observation.start(RECALL_OBSERVATION_NAME, observations).contextualName("recall");
      try (var _ = observation.openScope()) {
        List<Message> contribution = memory.recall(state);
        List<Message> candidate = concat(accepted, contribution);
        Context.of(candidate); // validates this contributor's addition; throws on pair-breaking
        accepted = candidate;
      } catch (RuntimeException e) {
        observation.error(e);
        hub.emit(new RecallFailed(state.id(), describe(e)));
      } finally {
        observation.stop();
      }
    }
    return accepted;
  }

  private static List<Message> concat(List<Message> head, List<Message> tail) {
    List<Message> combined = new ArrayList<>(head.size() + tail.size());
    combined.addAll(head);
    combined.addAll(tail);
    return combined;
  }

  private static String describe(RuntimeException e) {
    String message = e.getMessage();
    return message == null
        ? e.getClass().getSimpleName()
        : e.getClass().getSimpleName() + ": " + message;
  }

  /**
   * Where a pipeline's recalled contributions land relative to the shaped transcript.
   *
   * <p>There was never science behind project-then-recall ordering; both contributors key on the
   * ledger ({@link SessionState}), so composition order is a declared policy, not an accident of
   * implementation order.
   */
  public enum Placement {

    /**
     * Recalled messages come first, ahead of the shaped transcript. The default. Recalled content
     * changes turn to turn, and front-of-prompt injection churns the prompt-cache prefix every time
     * it changes — a refresh-on-compaction recall strategy aligns that churn with the churn
     * compaction already causes, instead of adding a second, independent one.
     */
    MEMORIES_FIRST,

    /**
     * Recalled messages come last, after the shaped transcript. Keeps the transcript — the part
     * most likely to be prompt-cached stably — at the front, at the cost of putting recalled
     * material where a model's recency bias weighs it more heavily.
     */
    MEMORIES_LAST
  }

  /** Collects one agent's pipeline bindings, in declaration order, before {@link #build}. */
  public static final class Builder {

    private final List<Memory> recalls = new ArrayList<>();
    private final List<Shape> shapes = new ArrayList<>();
    private Placement placement = Placement.MEMORIES_FIRST;

    private Builder() {}

    /** Adds one recall contributor. Contributions concatenate in declaration order. */
    public Builder recall(Memory memory) {
      recalls.add(Objects.requireNonNull(memory, "memory must not be null"));
      return this;
    }

    /** Adds one shape. Shapes apply in declaration order. */
    public Builder shape(Shape shape) {
      shapes.add(Objects.requireNonNull(shape, "shape must not be null"));
      return this;
    }

    /**
     * Where recalled contributions land relative to the shaped transcript. Default: {@link
     * Placement#MEMORIES_FIRST}.
     */
    public Builder placement(Placement placement) {
      this.placement = Objects.requireNonNull(placement, "placement must not be null");
      return this;
    }

    /**
     * Builds the pipeline. {@code hub} and {@code observations} are engine infrastructure, not part
     * of the declarative pipeline shape itself, which is why they arrive here rather than through
     * the builder's fluent methods.
     */
    public ContextPipeline build(EventHub hub, ObservationRegistry observations) {
      return new ContextPipeline(
          List.copyOf(recalls),
          List.copyOf(shapes),
          placement,
          Objects.requireNonNull(hub, "hub must not be null"),
          Objects.requireNonNull(observations, "observations must not be null"));
    }
  }
}
