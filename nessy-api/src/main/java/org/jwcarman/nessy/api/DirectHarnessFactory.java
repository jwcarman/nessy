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

package org.jwcarman.nessy.api;

import org.jwcarman.codec.TypeRef;

/**
 * Makes direct harnesses that share what an application owns once.
 *
 * <p>Where events are written, where content is kept, who provides inference, and what keeps two
 * callers off one agent are settled here; a harness adds what is its own -- a type, a prompt, its
 * tools, what it is shown, and now the shape it answers in.
 *
 * <p><b>An implementation may be closeable</b>, which is a narrower difference from {@link
 * QueuedHarnessFactory} than it once was. That one owns a timer and a poller because work is meant
 * to outlive the call that submitted it. Here a turn still ends when {@code ask} returns -- but a
 * deadline is enforced by waiting for each effect on a thread of its own, so what can outlive the
 * call is an abandoned effect whose caller has already been told it failed. A caller that never
 * closes loses nothing; a container that manages the lifecycle should.
 */
public interface DirectHarnessFactory {

  /**
   * A harness that answers in the shape {@code answers} names.
   *
   * <p>The schema is generated once, here, from {@code answers} -- not per call -- because the
   * shape a harness answers in is a fact about the harness, settled when it is made, the same way
   * {@link QueuedHarnessFactory} settles what an agent takes. Every turn this harness runs asks the
   * provider to constrain its answer to that shape and parses what comes back into {@code O} before
   * handing it over.
   *
   * @param <I> what a caller hands in. There is no type token for it: nothing here is written down
   *     as {@code I}, so nothing needs a codec for it. What is stored is what the renderer made of
   *     it.
   * @param <O> what a caller gets back. Unlike {@code I} this needs a token -- {@link TypeRef}
   *     rather than {@link Class} because a shape may itself be generic, such as a list of records
   *     -- because the shape crosses into a schema a provider is asked to honour.
   */
  <I, O> DirectHarness<I, O> create(
      AgentType agentType, TypeRef<O> answers, Customizer<DirectHarnessConfig<I>> customizer);

  /**
   * The same, reading the answer some way other than parsing it as JSON.
   *
   * <p>Supplied here rather than through {@link DirectHarnessConfig} because a reader is bound to
   * {@code O}, and {@code O} is named here: the shape a harness answers in and how that shape is
   * read back are one decision, and a config parameterised only on {@code I} could not state the
   * second half of it.
   *
   * <p>{@code answers} still decides the schema the provider is asked to honour, so the reader is
   * how a model that honours a shape while spelling it differently -- XML, a vendor's envelope, a
   * format that needs binding rules the shared mapper does not have -- still lands as an {@code O}.
   *
   * @param reader how the model's text becomes an {@code O}; see {@link OutputReader#json} for what
   *     the other overload uses, and {@link OutputReader#text} for the words as they came
   */
  <I, O> DirectHarness<I, O> create(
      AgentType agentType,
      TypeRef<O> answers,
      OutputReader<O> reader,
      Customizer<DirectHarnessConfig<I>> customizer);

  /** The common case: a shape with no type arguments to capture. */
  default <I, O> DirectHarness<I, O> create(
      AgentType agentType, Class<O> answers, Customizer<DirectHarnessConfig<I>> customizer) {
    return create(agentType, TypeRef.of(answers), customizer);
  }

  /** A shape with no type arguments to capture, read some way other than as JSON. */
  default <I, O> DirectHarness<I, O> create(
      AgentType agentType,
      Class<O> answers,
      OutputReader<O> reader,
      Customizer<DirectHarnessConfig<I>> customizer) {
    return create(agentType, TypeRef.of(answers), reader, customizer);
  }

  /**
   * A harness that asks nothing of the answer's shape.
   *
   * <p>Not a shorthand for {@link #create(AgentType, Class, Customizer)} with some magic type: no
   * schema reaches the provider, and what comes back is the prose of the answer -- the text blocks,
   * joined -- rather than something parsed. Anything else the model produced -- thinking, a
   * vendor's own opaque blocks -- is in the story and not in this string.
   *
   * @param <I> what a caller hands in
   */
  <I> DirectHarness<I, String> create(
      AgentType agentType, Customizer<DirectHarnessConfig<I>> customizer);
}
