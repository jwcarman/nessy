package org.jwcarman.nessy.api;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Something that has background to offer about an agent, asked afresh every time it is called.
 *
 * <p>The half of a module that speaks: a notebook the agent writes to with tools reads back through
 * one of these, a planner offers the plan it is holding, a clock offers the time. Tool in,
 * background out -- that pairing is the shape of most things worth building on top of an agent, and
 * this is the second half of it.
 *
 * <p><b>Asked on the dispatcher's thread, off the agent's row lock, once per call to the model.</b>
 * So it may do I/O -- read a table, call a service -- and it should expect to be asked again on the
 * very next turn. Nothing is cached on its behalf, because the whole point of background is that it
 * can have changed.
 *
 * <p><b>Empty is the right answer for nothing to say.</b> An {@link Ambient} with no content is
 * refused precisely so that a source with nothing to add returns none rather than an empty section:
 * a label with nothing under it reads to a model as "your notebook is empty", which is a claim,
 * where absence is not.
 *
 * <p>Two sources must not offer the same {@link #kind()}. An adapter would write two sections under
 * one label and the model would see a contradiction with no way to tell which is current, so it is
 * refused when the harness is built rather than resolved at render time -- which is why the kind is
 * declared here, once, rather than discovered from each answer.
 */
public interface AmbientSource {

  /**
   * What this source contributes, as a section label: {@code notebook}, {@code plan}, {@code
   * episodes}. Fixed for the life of the source, because it is what makes two of them a collision.
   */
  String kind();

  /**
   * @param agentId whose background is being assembled; the same source serves every agent of a
   *     type, so what it offers is almost always a function of this
   */
  Optional<Ambient> forAgent(AgentId agentId);

  /** Background that is the same for every agent and every turn. */
  static AmbientSource constant(Ambient ambient) {
    Objects.requireNonNull(ambient, "ambient must not be null");
    return of(source -> source.kind(ambient.kind()).offering(_ -> Optional.of(ambient)));
  }

  /**
   * One made from a kind and a function, for a source small enough that a class of its own would be
   * ceremony: {@code of(a -> a.kind("clock").text(who -> Optional.of(today())))}.
   */
  static AmbientSource of(Consumer<AmbientSourceConfig> customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    AmbientSourceConfig config = new AmbientSourceConfig();
    customizer.accept(config);
    String kind = config.requiredKind();
    Function<AgentId, Optional<Ambient>> offering = config.requiredOffering();
    return new AmbientSource() {
      @Override
      public String kind() {
        return kind;
      }

      @Override
      public Optional<Ambient> forAgent(AgentId agentId) {
        return offering.apply(agentId);
      }
    };
  }
}
