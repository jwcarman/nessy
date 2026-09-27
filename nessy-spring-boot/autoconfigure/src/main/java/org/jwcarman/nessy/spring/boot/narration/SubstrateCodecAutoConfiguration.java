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

import org.jwcarman.codec.Codec;
import org.jwcarman.nessy.api.IdentityCodec;
import org.jwcarman.nessy.api.StorageCodecConfigurer;
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
 * raw {@code byte[] -> byte[]} transform on its own, as a {@link PayloadTransformer}. Both ask the
 * same {@link StorageCodecConfigurer} bean for the same transform, seeded with the same {@link
 * IdentityCodec#INSTANCE} -- one composition, read by two consumers, rather than two independent
 * copies of the same fold.
 *
 * <p>Taken through an {@link ObjectProvider} rather than as an ordinary parameter: this class runs
 * whenever Substrate is on the classpath, which is not the same condition as the JDBC backend being
 * present, so no {@link StorageCodecConfigurer} bean may exist at all. Absent one, nothing is
 * appended -- the same behaviour the default bean would give.
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
   * application using it keeps that and gets no second layer). With no {@link
   * StorageCodecConfigurer} bean at all, or one that appends nothing, the composed transform is the
   * identity -- the same behaviour Substrate's own default would give, so nothing here silently
   * turns narration encryption off.
   */
  @Bean
  @ConditionalOnMissingBean(PayloadTransformer.class)
  public PayloadTransformer nessySubstratePayloads(
      ObjectProvider<StorageCodecConfigurer> configurers) {
    StorageCodecConfigurer configurer = configurers.getIfAvailable(() -> original -> original);
    Codec<byte[]> transform = configurer.configure(IdentityCodec.INSTANCE);
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
}
