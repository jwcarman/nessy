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
package org.jwcarman.nessy.spring.boot.narration;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.jwcarman.codec.Codec;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.StorageConfig;
import org.jwcarman.substrate.core.transform.PayloadTransformer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * The bridge between the engine's storage transform and Substrate's journal.
 *
 * <p>The engine wants its storage transform baked into a {@code CodecFactory} (the one bean {@link
 * org.jwcarman.nessy.spring.boot.NessyAutoConfiguration#codecFactory} builds); Substrate wants the
 * raw {@code byte[] -> byte[]} transform on its own, as a {@link PayloadTransformer}. So this class
 * applies the same {@code Customizer<StorageConfig>} beans a second time, independently of the
 * {@code CodecFactory} bean, rather than publishing a bare {@code Codec<byte[]>} bean for both to
 * share -- a bean of that type names nothing in particular, which is exactly the problem the
 * deleted {@code StorageCodec} marker existed to solve.
 *
 * <p>Ordered before Substrate's auto-configuration because its
 * {@code @ConditionalOnMissingBean(PayloadTransformer.class)} default is evaluated as this
 * registers.
 */
@AutoConfiguration(
    beforeName = "org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration")
@ConditionalOnClass(name = "org.jwcarman.substrate.journal.JournalFactory")
public class SubstrateCodecAutoConfiguration {

  /**
   * The engine's storage transform, applied to the journal too -- across the board, so an
   * application that encrypts what its agents remember also encrypts what they said out loud. Only
   * when nothing has already supplied a transformer of its own (substrate-crypto declares one; an
   * application using it keeps that and gets no second layer). With no {@code
   * Customizer<StorageConfig>} beans declared, the composed transform is the identity -- the same
   * behaviour Substrate's own default would give, so nothing here silently turns narration
   * encryption off.
   */
  @Bean
  @ConditionalOnMissingBean(PayloadTransformer.class)
  public PayloadTransformer nessySubstratePayloads(
      ObjectProvider<Customizer<StorageConfig>> customizers) {
    ComposingStorageConfig config = new ComposingStorageConfig();
    customizers.orderedStream().forEach(customizer -> customizer.customize(config));
    Codec<byte[]> transform = config.identityIfUnset();
    return new PayloadTransformer() {
      @Override
      public byte[] encode(byte[] bytes) {
        return transform.encode(bytes);
      }

      @Override
      public byte[] decode(byte[] bytes) {
        return transform.decode(bytes);
      }
    };
  }

  /** Collects every appended transform into one, composed outward in append order. */
  private static final class ComposingStorageConfig implements StorageConfig {

    private @Nullable Codec<byte[]> transform;

    @Override
    public StorageConfig append(Codec<byte[]> next) {
      Objects.requireNonNull(next, "transform must not be null");
      transform = transform == null ? next : transform.andThen(next);
      return this;
    }

    /** What no customizer declared, made concrete: bytes untouched either way. */
    Codec<byte[]> identityIfUnset() {
      if (transform != null) {
        return transform;
      }
      return new Codec<>() {
        @Override
        public byte[] encode(byte[] bytes) {
          return bytes;
        }

        @Override
        public byte[] decode(byte[] bytes) {
          return bytes;
        }
      };
    }
  }
}
