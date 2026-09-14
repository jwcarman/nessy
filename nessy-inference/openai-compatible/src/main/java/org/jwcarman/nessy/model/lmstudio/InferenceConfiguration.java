package org.jwcarman.nessy.engine.inference;

import java.net.http.HttpClient;
import java.time.Duration;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Points the agent at a locally served, OpenAI-compatible model. */
@Configuration(proxyBeanMethods = false)
public class InferenceConfiguration {

  /**
   * How hard an inference is worth trying.
   *
   * <p>This belongs to the binding, not to the fold and not to the stored effect. An inference has
   * no effect on the world beyond tokens spent, so repeating an unobserved outcome is safe -- which
   * is a fact about <em>this binding's</em> work, and would be a different answer for a tool that
   * sends an email.
   *
   * <p>Keeping it here rather than in the effect row means the effect says only what to do, and a
   * change of mind about retries applies to work already queued instead of only to work decided
   * afterwards.
   */
  @Bean
  public RetryPolicy modelRetryPolicy(
      @Value("${nessy.inference.retry.max-attempts:3}") int maxAttempts,
      @Value("${nessy.inference.retry.delay:2s}") Duration delay,
      @Value("${nessy.inference.retry.multiplier:2.0}") double multiplier,
      @Value("${nessy.inference.retry.jitter:1s}") Duration jitter,
      @Value("${nessy.inference.retry.max-delay:30s}") Duration maxDelay) {
    return new RetryPolicy.Exponential(maxAttempts, delay, multiplier, jitter, maxDelay);
  }

  /**
   * The read timeout is generous but finite on purpose. A drainer thread blocked on an inference
   * that never answers holds a claimed effect indefinitely, and with nothing yet reclaiming
   * abandoned work that effect would never run again. A timeout turns a hung call into a recorded
   * failure, which the fold already knows how to handle.
   */
  @Bean
  public InferenceProvider inferenceProvider(
      @Value("${nessy.inference.base-url:http://localhost:1234}") String baseUrl,
      @Value("${nessy.inference.connect-timeout:5s}") Duration connectTimeout,
      @Value("${nessy.inference.read-timeout:120s}") Duration readTimeout) {
    JdkClientHttpRequestFactory requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(connectTimeout).build());
    requestFactory.setReadTimeout(readTimeout);
    RestClient restClient =
        RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    return new LmStudioInferenceProvider(restClient);
  }

  /**
   * The application's default terms. An agent type that wants a different model or a tighter
   * ceiling sets its own on its harness rather than needing another provider bean.
   */
  @Bean
  public InferenceOptions inferenceOptions(
      @Value("${nessy.inference.model-name}") String modelName,
      @Value("${nessy.inference.max-tokens:0}") int maxTokens) {
    return new InferenceOptions(modelName, maxTokens);
  }
}
