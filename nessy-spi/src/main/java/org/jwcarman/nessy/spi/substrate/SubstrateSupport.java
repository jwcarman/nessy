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
package org.jwcarman.nessy.spi.substrate;

/**
 * Package-private message/format constants shared across {@link Substrate}'s sibling nested record
 * types ({@code Document}, {@code Entry}, {@code Op.WriteDocument}, {@code Op.AppendEntry}). Kept
 * out of the {@link Substrate} interface body — an interface field is implicitly {@code public
 * static final}, and these are wire/error text, not published API — so the public surface stays
 * exactly what it was before this holder existed, mirroring {@link CodecSupport}'s precedent.
 */
final class SubstrateSupport {

  private SubstrateSupport() {}

  /** Shared {@link NullPointerException} message for a null {@code payload} argument. */
  static final String PAYLOAD_NULL_MESSAGE = "payload must not be null";

  /** Shared {@code toString()} field-separator label for a record's {@code payload} byte count. */
  static final String PAYLOAD_BYTES_LABEL = ", payloadBytes=";
}
