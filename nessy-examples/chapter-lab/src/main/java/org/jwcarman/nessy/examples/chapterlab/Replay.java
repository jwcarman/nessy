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
package org.jwcarman.nessy.examples.chapterlab;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;

/**
 * The scripted provider the replay runs on: it answers a turn with the reply that was recorded for
 * it, instantly, so the recording costs nothing to play back.
 *
 * <p>It also notes, while a turn is under way, the turn's id and whether it is the last of a
 * recorded session. Doing that here rather than after the turn is what lets a policy cut at a
 * session's end: the policy is asked the moment the turn ends, before the lab could tell it.
 */
final class Replay implements InferenceProvider {

  private final Set<TurnId> sessionEnds = ConcurrentHashMap.newKeySet();
  private final AtomicReference<TurnId> last = new AtomicReference<>();
  private volatile String reply = "";
  private volatile boolean endsSession;

  /** What the next turn will be answered with, and whether it ends a recorded session. */
  void next(String reply, boolean endsSession) {
    this.reply = reply;
    this.endsSession = endsSession;
  }

  /** The turns that ended a recorded session, as far as they have been played. Live. */
  Set<TurnId> sessionEnds() {
    return sessionEnds;
  }

  /** The turn most recently played. */
  TurnId lastTurn() {
    return last.get();
  }

  @Override
  public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
    TurnId id = request.context().activeTurn().id();
    last.set(id);
    if (endsSession) {
      sessionEnds.add(id);
    }
    return new InferenceResult.Answer(List.of(new Block.Text(reply)), Usage.unreported("recorded"));
  }
}
