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
 * <p><b>Structured is not sanitised.</b> A schema constrains the shape, not the content: a {@code
 * String} field will carry whatever text the document put in it, injection included. The safety
 * comes from the fields being as narrow as the job allows -- an enum rather than a string, a number
 * rather than a number written out -- and from what the privileged side does with them afterwards.
 * An extractor turns untrusted text into data; it does not make it harmless.
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
