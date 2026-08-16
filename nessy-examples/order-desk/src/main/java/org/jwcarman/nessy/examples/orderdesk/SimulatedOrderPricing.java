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
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The demo's fake catalog (the same coupon-tool ethos {@link Warehouse} already states: obviously
 * fake, structurally honest): every item costs a flat $150, so an order's total is simply its item
 * count times that unit price. Nothing here claims to be a real catalog lookup — it exists so
 * {@link RequestFulfillmentTool}'s effect has a dollar amount worth a policy's judgment, without a
 * second demo process.
 */
@Component
public class SimulatedOrderPricing implements OrderPricing {

  private static final BigDecimal UNIT_PRICE = new BigDecimal("150.00");

  @Override
  public BigDecimal totalFor(List<String> items) {
    return UNIT_PRICE.multiply(BigDecimal.valueOf(items.size()));
  }
}
