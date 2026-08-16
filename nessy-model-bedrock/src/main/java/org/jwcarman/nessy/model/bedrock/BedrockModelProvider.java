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
package org.jwcarman.nessy.model.bedrock;

import java.util.Objects;
import java.util.Set;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;

/**
 * The public face of the Bedrock provider module: turns a {@link ModelRequest} into a live
 * streaming {@code ConverseStream} call against Amazon Bedrock via the AWS SDK for Java v2's {@code
 * bedrockruntime} client.
 *
 * <p>Everything upstream of this class is pure translation ({@link BedrockRequests}, {@link
 * BedrockStream}); this class is the one place that owns a {@link BedrockClient} and actually talks
 * to the network — including the async-to-blocking bridge {@link BedrockProviderConfig#wrap}
 * builds, since the SDK's {@code converseStream} is a push-callback API and {@link ModelStream} is
 * a blocking {@code Iterable} (see {@link BedrockClient}'s class javadoc).
 *
 * <p>{@link Capability#PARALLEL_TOOL_CALLS} is advertised: Bedrock's Converse API already streams
 * several {@code toolUse} content blocks in one assistant turn (one {@code content_block_start}
 * through {@code content_block_stop} span per block, each on its own {@code contentBlockIndex}),
 * and {@link BedrockRequests}/{@link BedrockStream} already handle that shape in both directions.
 * There is no dedicated {@code TOOLS} entry in {@link Capability} to also advertise — the enum only
 * tracks capabilities a provider might lack, and every provider module here already handles tool
 * calls unconditionally, so nothing further is claimed for plain (non-parallel) tool use beyond
 * what {@link Capability#PARALLEL_TOOL_CALLS} alone already communicates. {@link
 * Capability#THINKING}, {@link Capability#PROMPT_CACHING}, and {@link Capability#IMAGE_INPUT} are
 * deliberately absent: none is wired into this module's request/response mapping, so none is
 * claimed — the same discipline {@code GeminiModelProvider} documents for its own unadvertised
 * capabilities.
 *
 * <p>Also {@link AutoCloseable}: the real {@link BedrockClient} built by {@link
 * BedrockProviderConfig#wrap} owns a {@code BedrockRuntimeAsyncClient}, whose default Netty
 * transport holds an event-loop group and connection pool that outlive a single {@link #stream}
 * call. {@link ModelProvider} itself declares no {@code close()} — most sibling providers wrap a
 * client with no such teardown need — so this is additive: callers that construct a {@code
 * BedrockModelProvider} directly (rather than through a DI container that already manages its
 * lifecycle) should close it when done, the same as they would the underlying SDK client itself.
 *
 * <p><b>Close ownership is not symmetric across {@link BedrockProviderConfig}'s two client
 * paths.</b> {@link #close()} closes the {@code BedrockRuntimeAsyncClient} only when this provider
 * built it itself (the {@code region}/{@code credentialsProvider}/{@code fromEnv()} path); a client
 * handed in through {@link BedrockProviderConfig#client(BedrockRuntimeAsyncClient)} is never closed
 * here — see that method's javadoc. The two paths are not independent alternatives for who does the
 * closing, only for who does the building.
 */
public final class BedrockModelProvider implements ModelProvider, AutoCloseable {

  private static final Set<Capability> CAPABILITIES = Set.of(Capability.PARALLEL_TOOL_CALLS);

  private final BedrockClient client;

  BedrockModelProvider(BedrockClient client) {
    this.client = client;
  }

  /**
   * The blessed one-call shape: equivalent to {@code create(BedrockProviderConfig::fromEnv)}. Uses
   * the AWS SDK's own default credentials provider chain and resolves the region from {@code
   * AWS_REGION}/{@code AWS_DEFAULT_REGION} — see {@link BedrockProviderConfig#fromEnv()}.
   */
  public static BedrockModelProvider fromEnv() {
    return create(BedrockProviderConfig::fromEnv);
  }

  /**
   * Builds a {@link BedrockModelProvider} from a live {@link BedrockProviderConfig}: {@code
   * customizer} fills it in, then this factory validates its required field and constructs the
   * finished provider. No public {@code build()} survives here; the factory is the only place a
   * {@link BedrockProviderConfig} ever turns into a {@link BedrockModelProvider} (design of record
   * 2026-08-16 §1).
   */
  public static BedrockModelProvider create(BedrockProviderCustomizer customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    BedrockProviderConfig config = new BedrockProviderConfig();
    customizer.customize(config);
    return config.build();
  }

  @Override
  public ModelStream stream(ModelRequest request) {
    return client.converseStream(BedrockRequests.toRequest(request));
  }

  @Override
  public Set<Capability> capabilities() {
    return CAPABILITIES;
  }

  @Override
  public String name() {
    return "Bedrock";
  }

  @Override
  public void close() {
    client.close();
  }
}
