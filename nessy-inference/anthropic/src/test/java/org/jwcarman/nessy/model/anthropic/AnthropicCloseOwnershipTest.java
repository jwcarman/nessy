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
package org.jwcarman.nessy.model.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.client.AnthropicClient;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Who closes the SDK client (ruled 2026-08-26). A gateway must close the client it BUILT — its
 * OkHttp pool and dispatcher threads outlive the process otherwise — and must never close one the
 * application handed in through {@link AnthropicProviderConfig#client(AnthropicClient)}, which the
 * application still owns.
 *
 * <p>The recording client is a JDK dynamic {@link Proxy}, not a mocking library (the no-mocking
 * promise): {@link AnthropicClient} is an interface, so one can stand in for it and count {@code
 * close()} calls.
 */
class AnthropicCloseOwnershipTest {

  @Test
  void a_supplied_client_is_never_closed_by_the_provider() {
    AtomicInteger closes = new AtomicInteger();
    AnthropicClient supplied = recordingClient(closes);

    AnthropicModelProvider provider = AnthropicModelProvider.create(c -> c.client(supplied));
    provider.close();

    assertThat(closes).hasValue(0);
  }

  @Test
  void a_client_the_provider_built_itself_is_closed() {
    AtomicInteger closes = new AtomicInteger();
    AnthropicClient built = recordingClient(closes);
    // The apiKey path with the built client swapped in at the constructor — the same seam
    // AnthropicProviderConfig#build() reaches when it builds an AnthropicOkHttpClient itself.
    AnthropicModelProvider provider = new AnthropicModelProvider(built, 1024, true);

    provider.close();

    assertThat(closes).hasValue(1);
  }

  /** Closing twice releases once more, harmlessly — the SDK's own close is idempotent. */
  @Test
  void closing_an_owned_provider_twice_is_harmless() {
    AtomicInteger closes = new AtomicInteger();
    AnthropicModelProvider provider =
        new AnthropicModelProvider(recordingClient(closes), 1024, true);

    provider.close();
    provider.close();

    assertThat(closes).hasValue(2);
  }

  private static AnthropicClient recordingClient(AtomicInteger closes) {
    return (AnthropicClient)
        Proxy.newProxyInstance(
            AnthropicClient.class.getClassLoader(),
            new Class<?>[] {AnthropicClient.class},
            (proxy, method, args) -> {
              if ("close".equals(method.getName())) {
                closes.incrementAndGet();
                return null;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }
}
