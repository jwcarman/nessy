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
package org.jwcarman.nessy.examples.orderdesk;

import java.util.Optional;
import org.jwcarman.nessy.api.tool.authorization.AuthorizationContext;
import org.jwcarman.nessy.api.tool.authorization.Enricher;
import org.jwcarman.nessy.api.tool.authorization.Key;

/**
 * An effect-aware assessor (design of record 2026-08-16-authorization §4): a big basket is more
 * expensive to expedite and worth a second look before the desk ships it without a human glancing
 * at it, so an order of {@value #RUSH_ITEM_COUNT} items or more is flagged for {@link
 * OrderApprovalPolicy} to judge under its own, stricter threshold. Reads only the tool's own
 * rendered effect — no I/O, unlike the principal/quota exchanges enrichers may also do — the
 * simplest honest member of the species.
 */
final class RushOrderEnricher implements Enricher<RequestFulfillmentTool.FulfillmentEffect> {

  /** The deposit {@link OrderApprovalPolicy} reads back — this package's own escape-hatch key. */
  static final Key<Boolean> RUSH_ORDER = new Key<>(Boolean.class, "order-desk.rush-order");

  private static final int RUSH_ITEM_COUNT = 3;

  @Override
  public AuthorizationContext enrich(
      AuthorizationContext context, RequestFulfillmentTool.FulfillmentEffect effect) {
    boolean rush = effect.items().size() >= RUSH_ITEM_COUNT;
    return rush ? context.with(RUSH_ORDER, true) : context;
  }

  @Override
  public Optional<String> displayName() {
    return Optional.of("rush-order flag");
  }
}
