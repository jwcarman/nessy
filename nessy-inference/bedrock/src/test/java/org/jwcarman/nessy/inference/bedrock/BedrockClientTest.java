package org.jwcarman.nessy.inference.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;

/** The bridge from the SDK's future to one blocking call, without a network. */
@DisplayName("The Bedrock client seam")
class BedrockClientTest {

  private static final ConverseStreamRequest REQUEST =
      ConverseStreamRequest.builder().modelId("m").build();

  /** An async client whose converseStream answers with this future and nothing else works. */
  private static BedrockRuntimeAsyncClient answering(
      CompletableFuture<Void> future, AtomicBoolean closed) {
    return (BedrockRuntimeAsyncClient)
        Proxy.newProxyInstance(
            BedrockRuntimeAsyncClient.class.getClassLoader(),
            new Class<?>[] {BedrockRuntimeAsyncClient.class},
            (proxy, method, args) -> {
              if ("converseStream".equals(method.getName())) {
                return future;
              }
              if ("close".equals(method.getName())) {
                closed.set(true);
                return null;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  @Test
  void a_stream_that_completes_returns_and_an_owned_client_is_closed() {
    AtomicBoolean closed = new AtomicBoolean();
    BedrockClient client =
        BedrockClient.over(answering(CompletableFuture.completedFuture(null), closed), true);
    List<ConverseStreamOutput> seen = new ArrayList<>();

    assertThatCode(() -> client.converseStream(REQUEST, seen::add)).doesNotThrowAnyException();
    assertThat(seen).isEmpty();
    client.close();
    assertThat(closed).isTrue();
  }

  @Test
  void the_services_own_exception_is_thrown_rather_than_the_futures_wrapper() {
    ThrottlingException throttled = ThrottlingException.builder().message("slow down").build();
    BedrockClient client =
        BedrockClient.over(
            answering(CompletableFuture.failedFuture(throttled), new AtomicBoolean()), false);

    assertThatThrownBy(() -> client.converseStream(REQUEST, _ -> {})).isSameAs(throttled);
  }

  @Test
  void a_checked_cause_is_wrapped_so_it_still_surfaces_and_a_bare_wrapper_is_kept() {
    assertThat(BedrockClient.unwrap(new CompletionException(new IOException("gone"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("IOException")
        .hasMessageContaining("gone");
    CompletionException bare = new CompletionException("no cause", null);
    assertThat(BedrockClient.unwrap(bare)).isSameAs(bare);
  }

  @Test
  void a_client_handed_in_is_never_closed_here() {
    AtomicBoolean closed = new AtomicBoolean();
    BedrockClient.over(answering(CompletableFuture.completedFuture(null), closed), false).close();
    assertThat(closed).isFalse();
  }
}
