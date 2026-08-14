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
package org.jwcarman.nessy.examples.dispatcher;

import org.jwcarman.nessy.api.turn.TurnObserver;
import org.slf4j.Logger;

/**
 * The narration every driven segment shares — signal-driven ({@link SignalController}) and
 * callback-driven ({@link CallbackController}) alike: says-lines, tool activity, parks (spec §3's
 * "the app logs the park token" bullet), and now the segment's own ending too, all courtesy of
 * {@link TurnObserver#logging}, the factory this class used to hand-roll before collapsing onto it.
 */
final class IncidentLog {

  private IncidentLog() {}

  /**
   * @param label the log-line tag: the incident id when driven from {@link SignalController}, the
   *     park token when driven from {@link CallbackController} (that door never has the incident id
   *     in hand — only the token the crew was given).
   */
  static TurnObserver observer(String label, Logger logger) {
    return TurnObserver.logging(logger, "[" + label + "]");
  }
}
