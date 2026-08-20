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
package org.jwcarman.nessy.agent.host;

import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;

/**
 * The CLI's outlet: the wiring's observer composition is fixed at construction (§3.5), so the
 * per-turn waiter attaches through this relay. App-side outlet management, not core machinery (plan
 * decision 5). Events with no delegate are dropped — nobody was listening.
 */
public final class RelayTurnObserver implements TurnObserver {

  private final AtomicReference<TurnObserver> delegate = new AtomicReference<>();

  public void set(TurnObserver observer) {
    delegate.set(observer);
  }

  public void clear() {
    delegate.set(null);
  }

  @Override
  public void on(TurnEvent event) {
    TurnObserver current = delegate.get();
    if (current != null) {
      current.on(event);
    }
  }
}
