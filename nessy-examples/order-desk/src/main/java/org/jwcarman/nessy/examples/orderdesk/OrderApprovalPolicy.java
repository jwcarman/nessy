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

import java.math.BigDecimal;
import org.jwcarman.nessy.api.tool.PolicyDecision;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.api.tool.authorization.AuthorizationContext;

/**
 * The desk's own standing order (design of record 2026-08-16-authorization §5): fulfill routine
 * orders straight through, but ask a human before shipping anything expensive — and hold a rush
 * order (the {@link RushOrderEnricher}'s own deposit) to a stricter dollar line, since expediting a
 * big, costly basket is exactly the case worth a second look.
 */
final class OrderApprovalPolicy implements UsagePolicy<RequestFulfillmentTool.FulfillmentEffect> {

  static final BigDecimal STANDARD_THRESHOLD = new BigDecimal("500.00");
  static final BigDecimal RUSH_THRESHOLD = new BigDecimal("250.00");

  @Override
  public PolicyDecision evaluate(
      AuthorizationContext context, RequestFulfillmentTool.FulfillmentEffect effect) {
    boolean rush = context.get(RushOrderEnricher.RUSH_ORDER).orElse(false);
    BigDecimal threshold = rush ? RUSH_THRESHOLD : STANDARD_THRESHOLD;
    return effect.orderTotal().compareTo(threshold) > 0
        ? new PolicyDecision.RequireApproval()
        : new PolicyDecision.Allow();
  }
}
