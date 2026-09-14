package org.jwcarman.nessy.api;

import java.util.function.Consumer;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolConfig;

/**
 * How one agent type is set up.
 *
 * <p>A CONFIG, not a builder: fluent setters and no public {@code build()}. What produces a harness
 * is the factory, which holds the machinery a caller has no business assembling -- the stores, the
 * codecs, the transaction manager, the scheduler, the provider. A caller says what is different
 * about their agent type and nothing else.
 *
 * <p><b>Two things are required</b>, and they are the two that define an agent: what it is called
 * and what it is. Everything else has a default, either here or from the factory. Forgetting a poll
 * interval gets you 250ms; forgetting a system prompt would get you a generic assistant wearing
 * your agent type's name, working perfectly and doing the wrong job, with nothing in the logs to
 * say so.
 *
 * @param <O> the observation type this agent takes
 */
public interface HarnessConfig<O> {

  /** What this agent type is called. Names its rows and scopes its dispatcher's polling. */
  HarnessConfig<O> agentType(AgentType agentType);

  /** What this agent is, in the same words for every agent of the type. */
  HarnessConfig<O> systemPrompt(String prompt);

  /** What this agent is, worked out per agent. May do I/O; it runs off the row lock. */
  HarnessConfig<O> systemPrompt(SystemPromptSource source);

  /**
   * How an observation becomes something a model can read.
   *
   * <p>Defaults to {@link ObservationRenderer#asString()}, which is right for records and for
   * anything with a considered {@code toString}, and quietly wrong for a class without one -- that
   * sends {@code com.acme.Order@1a2b3c} to a model, and you pay for it.
   */
  HarnessConfig<O> observationRenderer(ObservationRenderer<O> renderer);

  /**
   * What the backlog becomes when an observation arrives while the agent is busy.
   *
   * <p>Defaults to {@link ObservationCoalescer#keepAll()} -- right for anything a person said,
   * wrong for a sensor, and only the application knows which it has.
   */
  HarnessConfig<O> observationCoalescer(ObservationCoalescer<O> coalescer);

  /** Adjusts how this agent type infers, using the factory's provider. */
  HarnessConfig<O> inference(Consumer<InferenceConfig> customizer);

  /** Adjusts how this agent type performs the work it owes itself. */
  HarnessConfig<O> effects(Consumer<EffectsConfig> customizer);

  /**
   * Offers background the model should have in mind, asked afresh on every call.
   *
   * <p>The other half of a tool. A notebook the agent writes to is a tool and one of these; so is a
   * plan it keeps, or a view of a system it is operating. Tool in, background out.
   *
   * <p>Two sources may not offer the same {@link Ambient#kind()} -- refused here rather than at
   * render time, because an adapter would write two sections under one label and the model would
   * see a contradiction with no way to tell which is current.
   */
  HarnessConfig<O> ambient(AmbientSource source);

  /** Background that is the same for every agent and every turn. */
  default HarnessConfig<O> ambient(Ambient ambient) {
    return ambient(AmbientSource.constant(ambient));
  }

  /** Offers a tool, and says what a call of it is worth. */
  <I> HarnessConfig<O> tool(Tool<I> tool, Consumer<ToolConfig<I>> customizer);

  default <I> HarnessConfig<O> tool(Tool<I> tool) {
    return tool(tool, Customizers.withDefaults());
  }
}
