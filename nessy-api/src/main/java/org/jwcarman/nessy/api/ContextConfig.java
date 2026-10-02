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
package org.jwcarman.nessy.api;

import java.time.Duration;

/**
 * How the context for a call is built: how a long history is cut into chapters and summarised, how
 * much of the tail is sent whole, and what background rides along.
 *
 * <p>The story an agent is sent is built in one fixed order, and these are its parts:
 *
 * <pre>
 *   summaries   the engine's own: one per closed chapter, oldest first
 *   tail        the completed turns after the last summary, at most {@link #maxTail}
 *   memory      what every {@link MemorySource} recalled as bearing on this turn
 *   state       the agent's standing situation, from every {@link StateSource}
 *   active      the turn being answered, whole
 *   ambient     whatever every {@link AmbientSource} has to say right now
 * </pre>
 *
 * <p><b>Who decides what.</b> A {@link ChapterPolicy} decides where the history is cut into
 * chapters, and a {@link Summarizer} writes the text that stands in for each closed chapter. Both
 * are asked when a turn ends, off the agent's own thread, so a model's call never waits on either.
 * Until a chapter's summary is written its turns stay in the tail, whole, so nothing is ever hidden
 * before it is replaced.
 *
 * <p><b>Three numbers, and how they relate.</b> {@link #chapterPolicy} says when to cut; {@link
 * #maxChapterLength} bounds the turns in any one chapter, whatever the policy says, so a summariser
 * is never shown more than that; {@link #maxTail} bounds the tail. The tail must be longer than a
 * chapter can be, or a chapter could be hidden from the tail before it was summarised, and a
 * harness whose numbers say otherwise is refused when it is built.
 *
 * <p>Chapters are on unless {@link #withoutChapters()} is called. Between it and {@link
 * #chapterPolicy} or {@link #summarizer}, which turn them back on, the last call wins.
 *
 * <p>Nothing here reads history except the summaries and the tail.
 */
public interface ContextConfig {

  /**
   * At most this many completed turns of the tail, counting back from the newest, whole. The turn
   * being answered is sent besides, and is not counted.
   *
   * <p>The tail is the completed turns after the last summarised chapter; with no summary it is the
   * whole story, cut to this many turns. Turns rather than tokens: an estimate written when an
   * entry is stored and the provider's tokenizer never agree, so a token budget is a number that
   * cannot be checked against the one that decides whether a request is accepted. Defaults to 40.
   */
  ContextConfig maxTail(int turns);

  /**
   * Where this agent's history is cut into chapters. Defaults to {@link ChapterPolicy#every(int)}
   * at twenty.
   */
  ContextConfig chapterPolicy(ChapterPolicy policy);

  /**
   * What writes the text that stands in for a closed chapter. Defaults to a prose summary written
   * by this agent's own model.
   */
  ContextConfig summarizer(Summarizer summarizer);

  /** At most this many turns in one chapter. Defaults to 30. */
  ContextConfig maxChapterLength(int turns);

  /** How long the lease taken for one model call is believed held. Defaults to two minutes. */
  ContextConfig chapterLeaseTtl(Duration ttl);

  /** No chapters: nothing is cut or summarised, and history is the last maxTail completed turns. */
  ContextConfig withoutChapters();

  /**
   * Offers what was recalled because it bears on the turn being answered, asked afresh on every
   * call.
   *
   * <p>Two memory sources may not offer the same {@link Memory#kind()} -- refused here rather than
   * at render time. The same kind may be offered by a state or an ambient source.
   */
  ContextConfig memory(MemorySource source);

  /**
   * Offers the agent's standing situation, asked afresh on every call with the turn being answered
   * so that it can answer as of that turn's start.
   *
   * <p>Two state sources may not offer the same {@link State#kind()} -- refused here rather than at
   * render time. The same kind may be offered by a memory or an ambient source.
   */
  ContextConfig state(StateSource source);

  /**
   * Offers background the model should have in mind, asked afresh on every call.
   *
   * <p>The other half of a tool. A notebook the agent writes to is a tool and one of these; so is a
   * plan it keeps, or a view of a system it is operating. Tool in, background out.
   *
   * <p>Two ambient sources may not offer the same {@link Ambient#kind()} -- refused here rather
   * than at render time, because an adapter would write two sections under one label and the model
   * would see a contradiction with no way to tell which is current.
   */
  ContextConfig ambient(AmbientSource source);

  /** Background that is the same for every agent and every turn. */
  default ContextConfig ambient(Ambient ambient) {
    return ambient(AmbientSource.constant(ambient));
  }
}
