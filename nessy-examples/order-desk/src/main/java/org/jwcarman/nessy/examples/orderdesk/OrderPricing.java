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

/**
 * What one order costs — looked up by the tool itself rather than trusted from the model's own
 * arguments (design of record 2026-08-16-authorization §2: the effect is the tool's own trusted
 * statement). A real desk would call a pricing service here; this seam is where that call would go.
 */
public interface OrderPricing {

  /** The order's total, in US dollars, for the items it contains. */
  BigDecimal totalFor(List<String> items);
}
