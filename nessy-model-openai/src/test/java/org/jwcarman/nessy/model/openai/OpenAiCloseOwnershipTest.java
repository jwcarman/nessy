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
package org.jwcarman.nessy.model.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.client.OpenAIClient;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Who closes the SDK client (ruled 2026-08-26). A gateway must close the client it BUILT — its
 * OkHttp pool and dispatcher threads outlive the process otherwise — and must never close one the
 * application handed in through {@link OpenAiProviderConfig#client(OpenAIClient)}, which the
 * application still owns.
 *
 * <p>The recording client is a JDK dynamic {@link Proxy}, not a mocking library (the no-mocking
 * promise): {@link OpenAIClient} is an interface, so one can stand in for it and count {@code
 * close()} calls.
 */
class OpenAiCloseOwnershipTest {

  @Test
  void a_supplied_client_is_never_closed_by_the_provider() {
    AtomicInteger closes = new AtomicInteger();
    OpenAIClient supplied = recordingClient(closes);

    OpenAiModelProvider provider = OpenAiModelProvider.create(c -> c.client(supplied));
    provider.close();

    assertThat(closes).hasValue(0);
  }

  @Test
  void a_client_the_provider_built_itself_is_closed() {
    AtomicInteger closes = new AtomicInteger();
    OpenAiModelProvider provider = new OpenAiModelProvider(recordingClient(closes), "openai", true);

    provider.close();

    assertThat(closes).hasValue(1);
  }

  /** The shared gateway serves xAI too, and ownership does not vary with the provider name. */
  @Test
  void a_supplied_client_is_untouched_whichever_vendor_the_gateway_answers_for() {
    AtomicInteger closes = new AtomicInteger();
    OpenAiModelProvider provider = new OpenAiModelProvider(recordingClient(closes), "x_ai", false);

    provider.close();

    assertThat(provider.model("grok-4.6").provider()).isEqualTo("x_ai");
    assertThat(closes).hasValue(0);
  }

  private static OpenAIClient recordingClient(AtomicInteger closes) {
    return (OpenAIClient)
        Proxy.newProxyInstance(
            OpenAIClient.class.getClassLoader(),
            new Class<?>[] {OpenAIClient.class},
            (proxy, method, args) -> {
              if ("close".equals(method.getName())) {
                closes.incrementAndGet();
                return null;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }
}
