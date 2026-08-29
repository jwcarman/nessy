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
package org.jwcarman.nessy.spi.codec;

/**
 * Appends {@code byte[] -> byte[]} transforms — compression, encryption, checksums — to the chain
 * every stored payload passes through.
 *
 * <p>Declared once, where the harness is built, and applied by BOTH the actor serializer and {@code
 * Substrate}. Encryption is a deployment-wide decision, not a per-agent one, which is why this is
 * not on {@code HarnessConfig}.
 *
 * <p>Order of appending is order of application, and it is recorded in every payload — so adding a
 * transform later leaves existing data readable, and reordering does not quietly corrupt it.
 */
@FunctionalInterface
public interface CodecCustomizer {

  /** Appends this customizer's transforms to {@code chain}. */
  void customize(CodecPipeline.Chain chain);
}
