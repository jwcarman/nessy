package org.jwcarman.nessy.inference.bedrock;

import java.util.Objects;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClientBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@link BedrockInferenceProvider#create(BedrockProviderCustomizer)} hands a customizer: a
 * CONFIG, not a builder -- fluent setters, no public {@code build()}.
 *
 * <p>There is no {@code apiKey}. Bedrock is reached with AWS credentials, ambient on most machines,
 * which is also why the starter wires no bean for it: an application that wants Bedrock says so in
 * code, so a stray AWS profile can never route an application here by accident.
 */
public final class BedrockProviderConfig {

  private static final String AWS_REGION_ENV_VAR = "AWS_REGION";
  private static final String AWS_DEFAULT_REGION_ENV_VAR = "AWS_DEFAULT_REGION";

  private Region region;
  private AwsCredentialsProvider credentialsProvider;
  private BedrockRuntimeAsyncClient client;
  private boolean useEnv;
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
    return BedrockClient.over(builder.build(), true);
  }

  private Region resolveRegion() {
    if (region != null) {
      return region;
    }
    if (useEnv) {
      String value = System.getenv(AWS_REGION_ENV_VAR);
      if (value == null) {
        value = System.getenv(AWS_DEFAULT_REGION_ENV_VAR);
      }
      if (value != null) {
        return Region.of(value);
      }
    }
    throw new IllegalStateException(
        AWS_REGION_ENV_VAR
            + " (or "
            + AWS_DEFAULT_REGION_ENV_VAR
            + ") environment variable is not set; call region(...) or fromEnv(), or provide a"
            + " preconfigured client via client(...)");
  }
}
