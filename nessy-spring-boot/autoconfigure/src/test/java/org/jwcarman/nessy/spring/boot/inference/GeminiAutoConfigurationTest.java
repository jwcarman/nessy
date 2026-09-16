package org.jwcarman.nessy.spring.boot.inference;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("The Gemini auto-configuration")
class GeminiAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(GeminiAutoConfiguration.class));

  @Test
  void gemini_api_key_contributes_a_provider() {
    runner
        .withPropertyValues("gemini.api-key=test")
        .run(context -> assertThat(context).hasSingleBean(InferenceProvider.class));
  }

  @Test
  void googles_other_name_for_the_key_does_too() {
    runner
        .withPropertyValues("google.api-key=test")
        .run(context -> assertThat(context).hasSingleBean(InferenceProvider.class));
  }

  @Test
  void with_neither_key_there_is_no_bean() {
    runner.run(context -> assertThat(context).doesNotHaveBean(InferenceProvider.class));
  }

  @Test
  void an_applications_own_provider_wins() {
    runner
        .withPropertyValues("gemini.api-key=test")
        .withBean(InferenceProvider.class, () -> (request, narrator) -> null)
        .run(
            context ->
                assertThat(context)
                    .hasSingleBean(InferenceProvider.class)
                    .doesNotHaveBean("geminiInferenceProvider"));
  }
}
