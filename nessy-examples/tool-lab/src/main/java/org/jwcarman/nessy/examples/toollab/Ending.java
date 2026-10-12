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
package org.jwcarman.nessy.examples.toollab;

import java.util.Locale;

/** How a run ended. */
enum Ending {
  /** The model answered in text. */
  ANSWER,
  /** The model declined. */
  REFUSAL,
  /** The vendor failed the request, or every attempt at it threw. */
  FAULT,
  /** The reply was cut off at the output limit. */
  TRUNCATED,
  /** The model was still asking for tools after the last round. */
  EXHAUSTED;

  String label() {
    return name().toLowerCase(Locale.ROOT);
  }
}
