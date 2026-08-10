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
 * ContextPipeline} applies its declared projections in declaration order to the {@link Context}
 * minted from {@link org.jwcarman.nessy.api.conversation.ConversationState#messages()}, before any
 * enriched messages are composed in. Because a projection is pure, a throwing projection is the
 * application's own bug, not a runtime condition to absorb — {@link ContextPipeline#assemble} lets
 * it propagate rather than catching it, in contrast to {@link ContextEnricher#enrich}, which is
 * I/O-sanctioned and best-effort.
 *
 * <p>The empty projection list — no {@code project(...)} calls on a {@link ContextPipeline.Builder}
 * — is the identity transform: the model sees the full working set unchanged. There is no dedicated
 * {@code Projection.identity()} factory; the empty list already says it.
 *
 * <p>Standard projections are written as lambdas over {@link Context}'s edit algebra (§10.8) —
 * {@code ctx -> ctx.elideToolResults(2)} — proving the algebra sufficient; there are no opaque
 * projection classes to import.
 */
@FunctionalInterface
public interface Projection {

  Context apply(Context context);
}
