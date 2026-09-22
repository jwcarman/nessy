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

import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.engine.store.StorageCodec;
import org.jwcarman.substrate.core.transform.PayloadTransformer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one bean Substrate needs and nothing supplies.
 *
 * <p>Substrate's journal factory is conditional on a {@link CodecFactory} bean, and no released
 * Substrate or Odyssey artifact declares one -- so without this, adding a backend gives a context
 * with no journal and, quietly, no Odyssey. Built from the application's mapper, and only when the
 * application has not built its own. Ordered before Substrate's auto-configuration because its
 * conditions are evaluated as it registers, and after Boot's Jackson so the mapper is the
 * application's.
 */
@AutoConfiguration(
    afterName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
    beforeName = "org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration")
@ConditionalOnClass(name = "org.jwcarman.substrate.journal.JournalFactory")
public class SubstrateCodecAutoConfiguration {

  // The application's mapper when Boot has made one by now, a plain one otherwise. Not a
  // condition on the bean: a condition is evaluated as this registers, and Boot's Jackson
  // auto-configuration is only ordered before this, not guaranteed to have won its own conditions.
  @Bean
  @ConditionalOnMissingBean
  public CodecFactory nessySubstrateCodecs(ObjectProvider<ObjectMapper> mappers) {
    return new JacksonCodecFactory(mappers.getIfAvailable(() -> JsonMapper.builder().build()));
  }

  /**
   * The engine's storage codec, applied to the journal too -- across the board, so an application
   * that encrypts what its agents remember also encrypts what they said out loud. Only when the
   * application declared one and no transformer of its own (substrate-crypto declares one; an
   * application using it keeps that and gets no second layer).
   */
  @Bean
  @ConditionalOnBean(StorageCodec.class)
  @ConditionalOnMissingBean(PayloadTransformer.class)
  public PayloadTransformer nessySubstratePayloads(StorageCodec storage) {
    return new PayloadTransformer() {
      @Override
      public byte[] encode(byte[] bytes) {
        return storage.encode(bytes);
      }

      @Override
      public byte[] decode(byte[] bytes) {
        return storage.decode(bytes);
      }
    };
  }
}
