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
import org.jwcarman.nessy.api.event.EnrichmentFailed;
import org.jwcarman.nessy.api.event.EventHub;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.session.SessionState;

/**
 * The Contextualize phase (§6.1), Maven-style: bindings declared once, at build time, in reviewable
 * code — never registered at runtime through the hub. Declaration order is execution order; the
 * same ledger and the same bindings always produce the same {@link Context}.
 *
 * <pre>{@code
 * harness.agent(SupportInput.class)
 *     .context(pipeline -> pipeline
 *         .project(Projection.elidingToolResults(2)) // PROJECT: 0..n, pure, declaration order
 *         .enrich(graphMemory)                        // ENRICH: 0..n contributors, best-effort
 *         .enrich(userPreferences)
 *         .placement(Placement.ENRICHMENTS_FIRST))     // where ENRICH contributions land
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
 *   <li><b>Project</b>: mints {@code Context.of(state.messages())} and applies every declared
 *       {@link Projection} in declaration order. A throwing projection propagates — projections are
 *       pure and total by contract, so a throw is the application's own bug, not a runtime
 *       condition to absorb.
 *   <li><b>Enrich</b>: runs each declared {@link ContextEnricher} contributor, in declaration
 *       order, under its own {@code nessy.context.enrich} observation. Each contributor is
 *       independently best-effort: a thrown exception, or a contribution that would break {@link
 *       Context}'s tool-pairing invariant when concatenated onto the contributions accepted so far,
 *       marks that contributor's observation with the error, emits {@link EnrichmentFailed}, and
 *       costs only that contributor's own contribution — every other contributor still runs. Zero
 *       declared contributors means zero observations and zero hub emissions, identical to the
 *       projected context alone.
 *   <li><b>Compose</b>: concatenates the accepted enrichment contributions with the projected
 *       transcript per {@link Placement}, and mints the result through {@code Context.of} one last
 *       time. This final validation is provably redundant given the two stages above — each
 *       enrichment contribution was validated as it was accepted, and projection always yields a
 *       valid {@link Context} by construction (the record's own invariant), so two independently
 *       valid, independently *closed* sequences (neither ending on a dangling {@code tool_use} nor
 *       opening on a stray {@code tool_result}) can never break pairing at the seam where they
 *       meet. It stays in as a defensive final check documenting that guarantee; if it ever did
 *       throw, that would mean the guarantee above no longer holds, which is a pipeline bug, not an
 *       enrichment failure — so, like a throwing projection, it is allowed to propagate rather than
 *       being attributed to any one contributor.
 * </ol>
 *
 * <p><b>Why project before enrich — jurisdiction, not sequence.</b> Enrichers key on the ledger,
 * not the projection, so ordering costs them nothing. Projections govern the <i>transcript's</i>
 * wire form; enriched material must be outside their reach — otherwise every projection would carry
 * a "don't touch the enrichments" clause. Project-then-enrich means projections see transcript
 * only, enrichments arrive verbatim, and {@link Placement} decides where they land.
 */
public final class ContextPipeline {

  private static final String ENRICH_OBSERVATION_NAME = "nessy.context.enrich";

  private final List<ContextEnricher> enrichers;
  private final List<Projection> projections;
  private final Placement placement;
  private final EventHub hub;
  private final ObservationRegistry observations;

  private ContextPipeline(
      List<ContextEnricher> enrichers,
      List<Projection> projections,
      Placement placement,
      EventHub hub,
      ObservationRegistry observations) {
    this.enrichers = enrichers;
    this.projections = projections;
    this.placement = placement;
    this.hub = hub;
    this.observations = observations;
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Assembles the {@link Context} one call against {@code state} sees. {@code state} carries both
   * the messages to project and the session id ({@link SessionState#id()}) that names the session
   * for the {@link EnrichmentFailed} event a failed enrichment contributor emits.
   */
  public Context assemble(SessionState state) {
    Context projected = applyProjections(Context.of(state.messages()));
    if (enrichers.isEmpty()) {
      return projected;
    }
    List<Message> enriched = collectEnrichmentContributions(state);
    if (enriched.isEmpty()) {
      return projected;
    }
    List<Message> composed =
        placement == Placement.ENRICHMENTS_FIRST
            ? concat(enriched, projected.messages())
            : concat(projected.messages(), enriched);
    return Context.of(composed);
  }

  private Context applyProjections(Context context) {
    Context projected = context;
    for (Projection projection : projections) {
      projected = projection.apply(projected);
    }
    return projected;
  }

  /**
   * Runs every declared enrichment contributor in order, folding each accepted contribution onto
   * the ones already accepted. Validating the running total after each contributor (rather than
   * only at the very end) is what lets a pair-breaking contribution be attributed to the one
   * contributor that caused it — the previously accepted contributions are known-good, so if
   * folding in the next one breaks {@link Context}'s pairing invariant, the new contributor is the
   * only thing that changed.
   */
  private List<Message> collectEnrichmentContributions(SessionState state) {
    List<Message> accepted = List.of();
    for (ContextEnricher enricher : enrichers) {
      Observation observation =
          Observation.start(ENRICH_OBSERVATION_NAME, observations).contextualName("enrich");
      try (var _ = observation.openScope()) {
        List<Message> contribution = enricher.enrich(state);
        List<Message> candidate = concat(accepted, contribution);
        Context.of(candidate); // validates this contributor's addition; throws on pair-breaking
        accepted = candidate;
      } catch (RuntimeException e) {
        observation.error(e);
        hub.emit(new EnrichmentFailed(state.id(), describe(e)));
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
   * Where a pipeline's enriched contributions land relative to the projected transcript.
   *
   * <p>There was never science behind project-then-enrich ordering; both contributors key on the
   * ledger ({@link SessionState}), so composition order is a declared policy, not an accident of
   * implementation order.
   */
  public enum Placement {

    /**
     * Enriched messages come first, ahead of the projected transcript. The default. Enriched
     * content changes turn to turn, and front-of-prompt injection churns the prompt-cache prefix
     * every time it changes — a refresh-on-compaction enrichment strategy aligns that churn with
     * the churn compaction already causes, instead of adding a second, independent one.
     */
    ENRICHMENTS_FIRST,

    /**
     * Enriched messages come last, after the projected transcript. Keeps the transcript — the part
     * most likely to be prompt-cached stably — at the front, at the cost of putting enriched
     * material where a model's recency bias weighs it more heavily.
     */
    ENRICHMENTS_LAST
  }

  /** Collects one agent's pipeline bindings, in declaration order, before {@link #build}. */
  public static final class Builder {

    private final List<ContextEnricher> enrichers = new ArrayList<>();
    private final List<Projection> projections = new ArrayList<>();
    private Placement placement = Placement.ENRICHMENTS_FIRST;

    private Builder() {}

    /** Adds one enrichment contributor. Contributions concatenate in declaration order. */
    public Builder enrich(ContextEnricher enricher) {
      enrichers.add(Objects.requireNonNull(enricher, "enricher must not be null"));
      return this;
    }

    /** Adds one projection. Projections apply in declaration order. */
    public Builder project(Projection projection) {
      projections.add(Objects.requireNonNull(projection, "projection must not be null"));
      return this;
    }

    /**
     * Where enriched contributions land relative to the projected transcript. Default: {@link
     * Placement#ENRICHMENTS_FIRST}.
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
          List.copyOf(enrichers),
          List.copyOf(projections),
          placement,
          Objects.requireNonNull(hub, "hub must not be null"),
          Objects.requireNonNull(observations, "observations must not be null"));
    }
  }
}
