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
package org.jwcarman.nessy.model.openai;

import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;

/**
 * Test-only sugar standing in for the retired {@code Reply#text()}: accumulates every {@link
 * TurnEvent.TextDelta} narrated during one {@code tell}, since the settled transcript now lives
 * only in {@code Memory} — not in {@code ConversationState} — after the cutover.
 */
final class TextObserver implements TurnObserver {

  private final StringBuilder text = new StringBuilder();

  @Override
  public void on(TurnEvent event) {
    if (event instanceof TurnEvent.TextDelta(String delta)) {
      text.append(delta);
    }
  }

  String text() {
    return text.toString();
  }
}
