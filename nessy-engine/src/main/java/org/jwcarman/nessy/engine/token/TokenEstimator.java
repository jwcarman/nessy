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
package org.jwcarman.nessy.engine.token;

import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.engine.history.HistoryEntry;

/**
 * Roughly how much of a model's context a message will occupy.
 *
 * <p>Estimated once, when the message is written, and stored beside it. That is what lets a budget
 * be applied in the query -- a running sum over turns, stopping at the oldest that fits -- instead
 * of loading a whole conversation to measure it and throw most of it away.
 *
 * <p><b>An estimate, and never the authority.</b> The provider's tokenizer decides, it differs by
 * model, and this counts content that has not been rendered to any wire yet. So a budget wants
 * headroom, and being wrong has to be survivable rather than merely unlikely. It is: an
 * underestimate means the request is refused for length, which is classified conservatively -- the
 * effect is kept, retried slowly, and heals when the budget is corrected. Nothing is lost.
 *
 * <p><b>Err high.</b> Overestimating wastes context; underestimating stalls a conversation until
 * someone notices. The two mistakes are not the same size.
 *
 * <p>It takes a message rather than a string because an observation is not always text. A run of
 * text is roughly its length over four; an image is a function of its dimensions; a document, of
 * its pages. Anything an implementation does not recognise should be charged generously rather than
 * counted as nothing.
 *
 * <p>Per agent type, alongside the model, since it is a property of what is being talked to. And
 * frozen at write: a better estimator does not retroactively correct old rows, though it could
 * recompute them, since the payload is still there.
 */
public interface TokenEstimator {

  /**
   * What one block costs. The unit of estimation, so a real tokenizer plugs in here and tokenizes
   * the content it is handed rather than being told how content was assembled.
   */
  int estimate(Block block);

  /**
   * What a whole message costs: its blocks, plus whatever the message itself costs once rendered
   * onto a wire. Written into the row when the message is written, so everything downstream budgets
   * against a number that was computed from the content exactly once.
   */
  int estimate(HistoryEntry message);
}
