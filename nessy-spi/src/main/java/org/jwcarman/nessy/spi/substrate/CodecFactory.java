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
 * The substrate-level codec seam (typed-stores spec §1 ruling 3): a {@link Substrate} exposes one
 * {@code CodecFactory} through {@link Substrate#codecs()}, and {@link Substrate#document(String,
 * Class)}/{@link Substrate#journal(String, Class)} derive their {@link Codec} from it. A
 * substrate's support base ({@link SubstrateSupport}) owns exactly one instance of this, backed by
 * exactly one pinned {@code ObjectMapper} — the statics-die law applied to codec construction: no
 * shared static mapper, one per substrate instance. Overriding the factory at substrate
 * construction (a caller-supplied {@code ObjectMapper}, or a wholly different {@code CodecFactory}
 * implementation) IS the codec extension point this reform retires the old per-feature codec
 * threading and the parked {@code .backlogCodec} seam into.
 */
public interface CodecFactory {

  /**
   * A {@link Codec} for {@code type}, bound however this factory is configured to bind it (spec §1
   * ruling 3) — the standard implementation is {@link Codec#json(com.fasterxml.jackson.databind.
   * ObjectMapper, Class)} over this factory's pinned mapper.
   */
  <T> Codec<T> codec(Class<T> type);
}
