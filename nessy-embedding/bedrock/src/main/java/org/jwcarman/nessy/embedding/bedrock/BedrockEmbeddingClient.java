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
package org.jwcarman.nessy.embedding.bedrock;

import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

/** The one call this module makes, as a seam a test can script without a credential. */
interface BedrockEmbeddingClient extends AutoCloseable {

  InvokeModelResponse invoke(InvokeModelRequest request);

  @Override
  void close();

  /** The real client; {@code owned} decides whether {@link #close()} closes it. */
  static BedrockEmbeddingClient over(BedrockRuntimeClient client, boolean owned) {
    return new BedrockEmbeddingClient() {
      @Override
      public InvokeModelResponse invoke(InvokeModelRequest request) {
        return client.invokeModel(request);
      }

      @Override
      public void close() {
        if (owned) {
          client.close();
        }
      }
    };
  }
}
