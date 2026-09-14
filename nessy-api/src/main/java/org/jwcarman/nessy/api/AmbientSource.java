package org.jwcarman.nessy.api;

import java.util.Optional;

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
 * <p>Two sources must not offer the same {@link Ambient#kind()}. An adapter would write two
 * sections under one label and the model would see a contradiction with no way to tell which is
 * current, so it is refused when the harness is built rather than resolved at render time.
 */
@FunctionalInterface
public interface AmbientSource {

  /**
   * @param agentId whose background is being assembled; the same source serves every agent of a
   *     type, so what it offers is almost always a function of this
   */
  Optional<Ambient> forAgent(AgentId agentId);

  /** Background that is the same for every agent and every turn. */
  static AmbientSource constant(Ambient ambient) {
    return _ -> Optional.of(ambient);
  }
}
