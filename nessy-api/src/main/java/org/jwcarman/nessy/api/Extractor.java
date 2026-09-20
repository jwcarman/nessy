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

/**
 * Reads an untrusted document for its fields, with a model given nothing to act with.
 *
 * <p>One call, no tools it can run, no memory and no history: a model is shown a document and a
 * shape, and the only thing it can do is fill the shape in. That is the whole of the isolation --
 * an invoice number and an amount cannot carry out an instruction, whatever the document asked for.
 *
 * <p><b>This does not prevent prompt injection, and is not trying to.</b> A document may well talk
 * a model into recording the wrong thing. What it cannot do is reach a model that can act: the
 * insulation is that the privileged inference -- the one holding real tools -- never sees the
 * document, only fields of a shape it asked for.
 *
 * <p><b>What comes back is a claim, not a fact.</b> A schema constrains the shape and says nothing
 * about the content: an invoice number is not true because it arrived in a {@code String}, and a
 * {@code String} carries whatever the document put in it, injection and all. Narrow fields -- an
 * enum rather than free text, a number rather than a number written out -- leave less room to carry
 * a payload onward, which is worth doing and is not trust.
 *
 * <p><b>Verify before acting.</b> An extracted field is a query, not an authorisation. Look the
 * invoice up; find it belongs to the customer who wrote in. What elevates a claim is agreement with
 * something already trusted, never the fact that a model said it neatly.
 *
 * <p>Deliberately not an agent. There is nothing to remember between documents, nothing to approve
 * and nothing to come back to, so there is no turn, no row and no fold.
 */
@FunctionalInterface
public interface Extractor {

  /**
   * Reads one document for the fields {@code type} declares.
   *
   * <p>Blocks, because there is nothing to wait for but the call itself. Answers rather than throws
   * when the model declines: with untrusted input, a model refusing to read a document is an
   * outcome worth branching on rather than a fault.
   *
   * @param type the shape to fill in, whose schema is what the model is shown
   * @param document the untrusted text, shown as content and never as instruction
   */
  <T> Extraction<T> extract(Class<T> type, String document);
}
