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
package org.jwcarman.nessy.api.message;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;

/**
 * Renders one application-typed input, {@code I}, into the {@link ContentBlock}s a {@code tell}
 * puts on the wire. Typing lives in the facade's generics and ends here — everything downstream
 * (the sealed {@link org.jwcarman.nessy.api.Event} grammar, the reducer, the engine) only ever sees
 * content blocks.
 *
 * <p>A renderer that returns {@code null} or an empty list, or that throws, fails the {@code tell}
 * call outright rather than degrading silently — see {@code Conversation#tell} for the exact
 * contract.
 *
 * @param <I> the input vocabulary this renderer knows how to render
 */
@FunctionalInterface
public interface InputRenderer<I> {

  /** Renders {@code input} into the content blocks its outbound {@code UserSaid} event carries. */
  List<ContentBlock> render(I input);

  /**
   * The pass-through renderer for {@code String} agents: raw text becomes exactly one {@link
   * TextBlock}, byte-for-byte what {@link Message#user(String)} and {@code Event.UserSaid.of}
   * already produce. The default for a {@code String} vocabulary.
   */
  static InputRenderer<String> text() {
    return text -> List.of(new TextBlock(text));
  }

  /**
   * The tagged-JSON renderer for typed vocabularies: a {@code [snake_case_simple_name]} tag line
   * naming the input's runtime record type, a newline, then the canonical JSON {@code mapper}
   * produces for it — one {@link TextBlock} carrying both. The default for any vocabulary other
   * than {@code String}.
   *
   * @param mapper the mapper the tag+JSON body is serialized with; typically the harness's own
   */
  static <I> InputRenderer<I> json(ObjectMapper mapper) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    return input -> {
      Objects.requireNonNull(input, "input must not be null");
      String tag = "[" + snakeCase(input.getClass().getSimpleName()) + "]";
      try {
        String json = mapper.writeValueAsString(input);
        return List.of(new TextBlock(tag + "\n" + json));
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException(
            "failed to render " + input.getClass().getName() + " as JSON", e);
      }
    };
  }

  /**
   * {@code OrderEscalation} → {@code order_escalation}; the tag-line naming rule for {@link #json}.
   */
  private static String snakeCase(String simpleName) {
    StringBuilder snake = new StringBuilder();
    for (int i = 0; i < simpleName.length(); i++) {
      char c = simpleName.charAt(i);
      if (Character.isUpperCase(c)) {
        if (i > 0) {
          snake.append('_');
        }
        snake.append(Character.toLowerCase(c));
      } else {
        snake.append(c);
      }
    }
    return snake.toString();
  }
}
