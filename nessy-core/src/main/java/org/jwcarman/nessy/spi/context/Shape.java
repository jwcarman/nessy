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

import org.jwcarman.nessy.api.message.Context;

/**
 * Transforms one {@link Context} into another — windows, redaction, elision, budgeting.
 *
 * <p>Pure and total: no I/O, no mutation, same output for the same input, every time. A {@link
 * ContextPipeline} applies its declared shapes in declaration order to the {@link Context} minted
 * from {@link org.jwcarman.nessy.api.session.SessionState#messages()}, before any recalled messages
 * are composed in. Because a shape is pure, a throwing shape is the application's own bug, not a
 * runtime condition to absorb — {@link ContextPipeline#assemble} lets it propagate rather than
 * catching it, in contrast to {@link org.jwcarman.nessy.spi.memory.Memory#recall}, which is
 * I/O-sanctioned and best-effort.
 *
 * <p>The empty shape list — no {@code shape(...)} calls on a {@link ContextPipeline.Builder} — is
 * the identity transform: the model sees the full working set unchanged. There is no dedicated
 * {@code Shape.identity()} factory; the empty list already says it.
 */
@FunctionalInterface
public interface Shape {

  Context apply(Context context);

  /**
   * Elides the content of tool results older than the last {@code keepRecentMessages} messages,
   * keeping the recent window verbatim. The first standard shape.
   *
   * @param keepRecentMessages how many of the most recent messages survive shaping untouched; must
   *     be at least 0
   */
  static Shape elidingToolResults(int keepRecentMessages) {
    return new ElidingToolResults(keepRecentMessages);
  }
}
