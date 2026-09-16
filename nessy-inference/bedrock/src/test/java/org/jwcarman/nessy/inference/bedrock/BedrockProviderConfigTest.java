package org.jwcarman.nessy.inference.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import tools.jackson.databind.json.JsonMapper;

/** Building a provider needs no network: the SDK client is constructed, never used. */
@DisplayName("The Bedrock provider config")
class BedrockProviderConfigTest {

  private static final StaticCredentialsProvider CREDENTIALS =
      StaticCredentialsProvider.create(AwsBasicCredentials.create("akid", "secret"));

  @Test
  void a_region_and_credentials_build_a_provider_that_closes_its_own_client() {
    BedrockInferenceProvider provider =
        BedrockInferenceProvider.create(
            c ->
                c.region(Region.US_EAST_1)
                    .credentialsProvider(CREDENTIALS)
                    .mapper(JsonMapper.builder().build()));

    assertThat(provider.name()).isEqualTo("Bedrock");
    assertThatCode(provider::close).doesNotThrowAnyException();
  }

  @Test
  void a_client_the_application_hands_in_is_used_and_never_closed_here() {
    AtomicBoolean closed = new AtomicBoolean();
    BedrockRuntimeClient theirs =
        (BedrockRuntimeClient)
            java.lang.reflect.Proxy.newProxyInstance(
                BedrockRuntimeClient.class.getClassLoader(),
                new Class<?>[] {BedrockRuntimeClient.class},
                (proxy, method, args) -> {
                  if ("close".equals(method.getName())) {
                    closed.set(true);
                  }
                  return null;
                });

    BedrockInferenceProvider.create(c -> c.client(theirs)).close();

    assertThat(closed).isFalse();
  }

  @Test
  void an_explicit_region_wins_over_the_environment() {
    assertThatCode(
            () ->
                BedrockInferenceProvider.create(
                        c -> c.fromEnv().region(Region.EU_WEST_1).credentialsProvider(CREDENTIALS))
                    .close())
        .doesNotThrowAnyException();
  }

  @Test
  void from_env_with_no_region_names_both_variables() {
    assumeTrue(
        System.getenv("AWS_REGION") == null && System.getenv("AWS_DEFAULT_REGION") == null,
        "a region is set in this environment");

    assertThatThrownBy(
            () ->
                BedrockInferenceProvider.create(c -> c.fromEnv().credentialsProvider(CREDENTIALS)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AWS_REGION")
        .hasMessageContaining("AWS_DEFAULT_REGION");
  }

  @Test
  void a_null_mapper_is_refused() {
    assertThatThrownBy(() -> BedrockInferenceProvider.create(c -> c.mapper(null)))
        .isInstanceOf(NullPointerException.class);
  }
}
