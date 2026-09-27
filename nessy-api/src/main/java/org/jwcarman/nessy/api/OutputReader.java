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

import java.util.Objects;
import org.jwcarman.codec.TypeRef;
import tools.jackson.databind.ObjectMapper;

/**
 * How the text a model produced becomes the object a caller asked for.
 *
 * <p>The counterpart to {@link InputRenderer}, at the other end of a turn: one says how {@code <I>}
 * reaches a model, this says how {@code <O>} comes back out of it. Text in, object out, and nothing
 * else in the signature -- where that text was kept, which agent said it, and what a failure to
 * read it means to the turn are all the framework's business, not this one's.
 *
 * <p><b>Throw rather than report.</b> A model that answered around the schema it was given has
 * produced something that does not fit {@code <O>}, and saying so is an exception, because the only
 * honest return value here is an {@code O}. The door catches it and decides what it costs the turn:
 * the caller asked for a shape precisely so it would not be handed prose instead, so that turn
 * fails rather than answering with something that does not fit.
 */
@FunctionalInterface
public interface OutputReader<O> {

  /**
   * @param answer what the model said, as text
   * @return that same answer as the shape the harness was created for
   * @throws RuntimeException if the text is not that shape
   */
  O read(String answer);

  /**
   * The default: the answer is JSON, and it parses into the type asked for.
   *
   * <p>What a harness gets unless it says otherwise, because a shape was asked for by naming a Java
   * type and the model was handed the schema generated from that same type -- so JSON matching it
   * is exactly what was requested. An application replaces this when its models answer in something
   * else, or when a type needs binding rules the shared mapper does not have.
   *
   * @param mapper the application's configured mapper, so an answer is read by the same rules
   *     everything else in the process is
   * @param type the shape asked for, including its type arguments
   */
  static <O> OutputReader<O> json(ObjectMapper mapper, TypeRef<O> type) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    Objects.requireNonNull(type, "type must not be null");
    return answer -> mapper.readValue(answer, mapper.constructType(type.getType()));
  }

  /**
   * The other case: whatever the model said, as it said it.
   *
   * <p>A harness created without naming a shape asked for no shape, so the model was given no
   * schema to answer against and there is nothing to parse -- the text IS the answer. Reading it as
   * JSON would fail on the first sentence of prose, which is the usual answer to an open question.
   *
   * <p>Worth having as a named reader rather than a lambda at the one call site that needs it,
   * because "no shape was asked for" is a case an application can deliberately choose too: a
   * harness typed on something else may still want the words, and a {@code Reader} that cannot say
   * so would push every such caller into writing the identity function themselves.
   */
  static OutputReader<String> text() {
    return answer -> answer;
  }
}
