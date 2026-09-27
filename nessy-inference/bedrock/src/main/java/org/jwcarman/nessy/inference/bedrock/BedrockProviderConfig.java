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
package org.jwcarman.nessy.inference.bedrock;

import java.time.Duration;
import java.util.Objects;
import org.jwcarman.nessy.api.Customizer;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClientBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@link BedrockInferenceProvider#create(Customizer<BedrockProviderConfig>)} hands a
 * customizer: a CONFIG, not a builder -- fluent setters, no public {@code build()}.
 *
 * <p>There is no {@code apiKey}. Bedrock is reached with AWS credentials, ambient on most machines,
 * which is also why the starter wires no bean for it: an application that wants Bedrock says so in
 * code, so a stray AWS profile can never route an application here by accident.
 */
public final class BedrockProviderConfig {

  private static final String AWS_REGION_ENV_VAR = "AWS_REGION";
  private static final String AWS_DEFAULT_REGION_ENV_VAR = "AWS_DEFAULT_REGION";

  // Fixed, not configurable (per this setter's own javadoc): we are already building the Netty
  // HTTP client to carry the read timeout below, so a sensible connect bound comes for free -- but
  // it is not the thing this setter is for, and exposing it would widen the surface for a knob
  // nobody has asked to tune.
  private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(5);

  private Region region;
  private AwsCredentialsProvider credentialsProvider;
  private BedrockRuntimeAsyncClient client;
  private boolean useEnv;
  private Duration timeout;
  private JsonMapper mapper = JsonMapper.builder().build();

  BedrockProviderConfig() {}

  /** The AWS region Bedrock requests are sent to. */
  public BedrockProviderConfig region(Region region) {
    this.region = region;
    return this;
  }

  /** Overrides the default AWS credentials provider chain. */
  public BedrockProviderConfig credentialsProvider(AwsCredentialsProvider credentialsProvider) {
    this.credentialsProvider = credentialsProvider;
    return this;
  }

  /**
   * The AWS SDK's own default credentials chain, and the region from {@value #AWS_REGION_ENV_VAR}
   * then {@value #AWS_DEFAULT_REGION_ENV_VAR}, Amazon's documented pair in that order. An explicit
   * {@link #region(Region)} still wins.
   */
  public BedrockProviderConfig fromEnv() {
    this.useEnv = true;
    return this;
  }

  /**
   * Escape hatch: a fully preconfigured SDK client instead of {@code region}/{@code
   * credentialsProvider}.
   *
   * <p><b>Ownership stays with the caller.</b> The provider closes only a client it built itself.
   */
  public BedrockProviderConfig client(BedrockRuntimeAsyncClient client) {
    this.client = client;
    return this;
  }

  public BedrockProviderConfig mapper(JsonMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    return this;
  }

  /**
   * The maximum time to wait on one Bedrock call, applied as BOTH {@code apiCallTimeout} (the
   * SDK-level bound) and the Netty HTTP client's own {@code readTimeout} -- both are required,
   * because {@code apiCallTimeout} alone leaves the transport's own 30-second socket read timeout
   * and its retries underneath it in charge, so the socket fires first and the API-level bound
   * never gets a chance to. Unset by default, so an application that never calls this keeps the AWS
   * SDK's own defaults (2 s connect, 30 s read, three to four retries). The starter that builds
   * this provider by default sets it to a margin above the engine's own {@code
   * InferenceConfig.timeout}.
   *
   * <p>Also fixes the connect timeout at a sensible five seconds on the Netty client this builds --
   * not exposed as a setting of its own, since nobody has asked to tune it and the read/API-call
   * bound above is the one that matters here.
   *
   * <p>Ignored when a preconfigured {@link #client(BedrockRuntimeAsyncClient)} is supplied: that
   * client is used exactly as given, and whatever timeout it already carries is the caller's own
   * business, not this config's to override.
   *
   * @throws IllegalArgumentException if {@code timeout} is zero or negative
   */
  public BedrockProviderConfig timeout(Duration timeout) {
    this.timeout = requirePositive(timeout);
    return this;
  }

  private static Duration requirePositive(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive, was " + timeout);
    }
    return timeout;
  }

  BedrockInferenceProvider build() {
    return new BedrockInferenceProvider(resolveClient(), mapper);
  }

  private BedrockClient resolveClient() {
    if (client != null) {
      return BedrockClient.over(client, false);
    }
    BedrockRuntimeAsyncClientBuilder builder =
        BedrockRuntimeAsyncClient.builder()
            .region(resolveRegion())
            .credentialsProvider(
                credentialsProvider != null
                    ? credentialsProvider
                    : DefaultCredentialsProvider.builder().build());
    if (timeout != null) {
      builder
          .overrideConfiguration(o -> o.apiCallTimeout(timeout))
          .httpClientBuilder(
              NettyNioAsyncHttpClient.builder()
                  .readTimeout(timeout)
                  .connectionTimeout(CONNECTION_TIMEOUT));
    }
    return BedrockClient.over(builder.build(), true);
  }

  private Region resolveRegion() {
    if (region != null) {
      return region;
    }
    // Said before reading anything, because the message below is about environment variables and
    // this caller never asked for them. It used to fall through to that message, so a caller who
    // simply forgot region(...) was told AWS_REGION was unset -- advice about a mechanism it had
    // not opted into.
    if (!useEnv) {
      throw new IllegalStateException(
          "a region is required: call region(...) or fromEnv(), or provide a preconfigured client"
              + " via client(...)");
    }
    String value = System.getenv(AWS_REGION_ENV_VAR);
    if (value == null) {
      value = System.getenv(AWS_DEFAULT_REGION_ENV_VAR);
    }
    if (value != null) {
      return Region.of(value);
    }
    throw new IllegalStateException(
        AWS_REGION_ENV_VAR
            + " (or "
            + AWS_DEFAULT_REGION_ENV_VAR
            + ") environment variable is not set; call region(...) or fromEnv(), or provide a"
            + " preconfigured client via client(...)");
  }
}
