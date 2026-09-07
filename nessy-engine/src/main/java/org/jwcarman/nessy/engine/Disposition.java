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
package org.jwcarman.nessy.engine;

import org.jwcarman.nessy.engine.agent.Effect;

/**
 * When an effect happens, relative to the transition that decided it.
 *
 * <p>The engine used to run every effect the same way -- fire and forget, on a blocking executor,
 * after the state was persisted. That is correct for exactly one of these three.
 */
public enum Disposition {

  /**
   * A row in {@code nessy_effect}, claimed and executed after commit.
   *
   * <p>Everything that leaves the process or must survive one. The test is not "is it slow" but "if
   * this node dies now, must someone else finish it?"
   *
   * <p>There used to be a third disposition here -- TRANSACTIONAL, written inside the transition
   * rather than as a row, for {@code SetAlarm}/{@code CancelAlarm} and the {@code nessy_reminder}
   * table they wrote to. It is gone: a parked call's deadline is now the SAME row this disposition
   * already governs -- {@code actionable_at} on the call's own {@code AskApprover}/{@code RunTool}
   * effect -- so there is one durable deadline per call, not two mechanisms that could disagree
   * about it.
   */
  DURABLE,

  /**
   * Delivered to whoever is watching, and never stored.
   *
   * <p>Losing a narration costs a watcher one line. Storing it costs a row per token for content
   * that the fact following it makes redundant.
   */
  NARRATION;

  /** Exhaustive over the grammar, with no default arm: a new effect must choose. */
  public static Disposition of(Effect effect) {
    return switch (effect) {
      case Effect.Narrate _ -> NARRATION;
      case Effect.TakeWork _,
          Effect.CallModel _,
          Effect.AskApprover _,
          Effect.RunTool _,
          Effect.Remember _,
          Effect.Release _,
          Effect.Forget _ ->
          DURABLE;
    };
  }
}
