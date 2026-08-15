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
package org.jwcarman.nessy.spi.memory;

import java.util.Objects;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ContextTransformer#optional(ContextTransformer)}'s decorator: makes {@code delegate}
 * best-effort by catching {@link RuntimeException} only — anything else (an {@link Error}, for
 * instance) is not this pipeline's business to swallow — and falling back to the input {@code
 * Context} unchanged, logging exactly one {@code WARN} naming the delegate and the conversation id
 * so a silently-skipped stage still leaves a trace.
 */
final class OptionalTransformer implements ContextTransformer {

  private static final Logger LOGGER = LoggerFactory.getLogger(OptionalTransformer.class);

  private final ContextTransformer delegate;

  OptionalTransformer(ContextTransformer delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
  }

  @Override
  public Context transform(ConversationId id, Context context) {
    try {
      return delegate.transform(id, context);
    } catch (RuntimeException e) {
      LOGGER.warn(
          "optional context stage {} failed for {}; continuing without it", delegate, id, e);
      return context;
    }
  }
}
