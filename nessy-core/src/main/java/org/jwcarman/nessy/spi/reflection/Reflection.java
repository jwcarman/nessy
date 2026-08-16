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
package org.jwcarman.nessy.spi.reflection;

import java.util.Objects;
import java.util.function.Consumer;
import org.jwcarman.nessy.api.ConversationSettled;

/**
 * The critic factory (design of record 2026-08-16 §3): a listener for {@link ConversationSettled}
 * that reviews a settled conversation with a side model call and writes distilled lessons into the
 * subject's notebook.
 *
 * <pre>{@code
 * harnessBuilder.listen(ConversationSettled.class,
 *     Reflection.critic(c -> c
 *         .transcript(transcript)
 *         .notebook(notebook)
 *         .subject(conversationId -> subjectFor(conversationId))
 *         .provider(provider)
 *         .model("claude-haiku-4-5-20251001")
 *         .reflectOnSuccess(false)));
 * }</pre>
 */
public final class Reflection {

  private Reflection() {}

  /**
   * Composes a critic: {@code customizer} fills in a live {@link ReflectionConfig}, then this
   * factory turns it into the finished {@code Consumer<ConversationSettled>}. No public {@code
   * build()} survives here; the factory is the only place a {@link ReflectionConfig} ever turns
   * into a critic (design of record 2026-08-16 §1).
   *
   * @throws IllegalStateException naming whichever required field {@code customizer} left unset
   */
  public static Consumer<ConversationSettled> critic(ReflectionCustomizer customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    ReflectionBuilder config = new ReflectionBuilder();
    customizer.customize(config);
    return config.build();
  }
}
