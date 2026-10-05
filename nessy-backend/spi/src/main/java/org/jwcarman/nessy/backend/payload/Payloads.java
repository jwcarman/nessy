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
package org.jwcarman.nessy.backend.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.block.Block;
import tools.jackson.databind.JsonNode;

/**
 * Where content lives, for the content the core never sees.
 *
 * <p>Everything an event would otherwise carry -- an input, a model's answer, a tool's result -- is
 * put here on the way in and fetched back on the way out. Events hold identifiers, status, human
 * decisions, counts and a {@link PayloadRef}, and for a tool call two bounded lines of text; this
 * holds the rest.
 *
 * <p>That split is what keeps a stream small enough to replay on every command, keeps every type in
 * it one of Nessy's own, and lets content expire on a different schedule from the record of what
 * happened.
 *
 * <p><b>Nothing here is governed.</b> A resolve finds the content or something is broken -- there
 * is no third answer. Where a value must not reach a model, it is a surrogate inside the content,
 * and the reveal happens in the tool that holds the charter, not here.
 */
public interface Payloads {

  /**
   * This store, for one agent's content.
   *
   * <p>Every payload an agent ever wrote is scoped to it, so removing an agent's payloads is one
   * statement over one table rather than a traversal of what it might share with others. That is
   * not everything the agent said: the lines in its events and the summaries of its chapters are
   * elsewhere. A store that keeps nothing beyond the process has nothing to scope and answers with
   * itself.
   */
  default Payloads forAgent(AgentId agent) {
    return this;
  }

  /**
   * Keeps content, and says where it went.
   *
   * <p><b>Idempotent.</b> Putting the same content twice is the same reference and one copy, so an
   * effect retried after a failure cannot leave a second one behind. The reference is a SHA-256 of
   * the content as the value codec writes it, before the storage transform, so the same content is
   * one reference and one copy whatever the transform does.
   */
  PayloadRef put(List<? extends Block> content);

  /**
   * Keeps a JSON document, and says where it went.
   *
   * <p>The same rules as {@link #put}: <b>idempotent</b>, so the same document is the same
   * reference and one copy, whatever the storage transform does -- the reference is a SHA-256 of
   * the document as the value codec writes it, before the transform. The document is stored as
   * given, its fields in the order it has them, so two callers that build the same document the
   * same way get the same reference.
   *
   * @param document a JSON object, array or value; never null and never JSON {@code null}
   */
  PayloadRef putDocument(JsonNode document);

  /**
   * What is behind a reference, when it is blocks.
   *
   * @throws IllegalStateException when the reference holds a document rather than blocks
   */
  Resolved get(PayloadRef ref);

  /**
   * The document behind a reference.
   *
   * @throws IllegalStateException when nothing is behind the reference, which is always a fault,
   *     and when what is there is blocks rather than a document
   */
  JsonNode getDocument(PayloadRef ref);

  /**
   * Everything behind these references, in one go.
   *
   * <p>Building a window of turns needs every payload in it, and asking one at a time is a round
   * trip per block -- free in memory, and the difference between one query and fifty over a
   * database. A store that can fetch a set should; the default asks one at a time, so an
   * implementation that has nothing better is still correct.
   *
   * @return what was found, keyed by reference. A reference with nothing behind it maps to {@link
   *     Resolved.Missing}, so the result always has an entry for every reference asked about.
   * @throws IllegalStateException when any reference holds a document rather than blocks
   */
  default Map<PayloadRef, Resolved> get(Collection<PayloadRef> refs) {
    Map<PayloadRef, Resolved> found = new LinkedHashMap<>();
    for (PayloadRef ref : refs) {
      found.computeIfAbsent(ref, this::get);
    }
    return found;
  }

  /**
   * The shape content takes once written down.
   *
   * <p>Here rather than in either store so both encode the SAME type: the reference is a hash of
   * the value codec's bytes, so a record declared twice -- once per backend -- would give the same
   * content two different references the day somebody renamed a component in one of them, and
   * nothing would fail until a reader compared the two.
   *
   * <p>The component is written whatever the application's mapper does with empty values, or an
   * empty list would encode as {@code {}} and not read back.
   */
  record Content(@JsonInclude(JsonInclude.Include.ALWAYS) List<Block> blocks) {}

  /**
   * The shape a document takes once written down: encoded on its own, apart from {@link Content},
   * and kept with a kind that says it is a document, so neither is ever decoded as the other.
   *
   * <p>The component is written whatever the application's mapper does with empty values, or an
   * empty object would encode as {@code {}} and not read back.
   *
   * @param document the JSON document; never null, JSON null or a missing node (a null nested
   *     inside it is fine)
   */
  record Document(@JsonInclude(JsonInclude.Include.ALWAYS) JsonNode document) {

    public Document {
      Objects.requireNonNull(document, "document must not be null");
      if (document.isNull() || document.isMissingNode()) {
        throw new IllegalArgumentException("a document must not be JSON null or missing");
      }
    }
  }

  /**
   * The reference for content that the value codec wrote as {@code bytes}: their SHA-256, taken
   * before the storage transform.
   *
   * <p>Derived rather than minted, which is what makes {@link #put} idempotent: the same content is
   * the same reference, so an effect retried after a failure cannot leave a second copy. Shared for
   * the same reason {@link Content} is -- two derivations would be two answers.
   */
  static PayloadRef reference(byte[] bytes) {
    try {
      return new PayloadRef(
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    } catch (NoSuchAlgorithmException impossible) {
      // Every JVM ships SHA-256; the checked exception is the API's age showing.
      throw new IllegalStateException("SHA-256 is not available", impossible);
    }
  }

  /** What came of asking. */
  sealed interface Resolved {

    record Found(List<Block> content) implements Resolved {}

    /** Nothing is there: a dangling reference, which is always a fault. */
    record Missing() implements Resolved {}
  }
}
