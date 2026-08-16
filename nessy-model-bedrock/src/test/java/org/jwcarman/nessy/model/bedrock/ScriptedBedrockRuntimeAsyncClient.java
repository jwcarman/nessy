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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;

/**
 * A hand-rolled {@link BedrockRuntimeAsyncClient} test double, driven entirely offline: {@code
 * converseStream} is the only method the real SDK exercises through {@link
 * BedrockModelProvider.Builder#wrap} (every other {@code BedrockRuntimeAsyncClient} operation is a
 * default method that this double never overrides and this module never calls), so overriding it —
 * plus the two abstract lifecycle methods {@code serviceName()}/{@code close()} — is the entire
 * seam needed to pin the async-to-blocking bridge's behavior without a network call, a real Netty
 * transport, or a mocking library.
 *
 * <p>Events are delivered to the response handler via {@link SdkPublisher#fromIterable(Iterable)} —
 * a real, synchronous, backpressure-satisfying {@code Publisher} the AWS SDK itself ships for
 * exactly this kind of offline construction — so the handler's built-in dispatch (subscribe →
 * {@code onNext} per event → {@code onComplete}) runs unchanged, on the calling thread, before
 * {@link #converseStream} returns.
 */
final class ScriptedBedrockRuntimeAsyncClient implements BedrockRuntimeAsyncClient {

  private final List<ConverseStreamOutput> events;
  private final Throwable failure;
  private final boolean leaveFuturePending;
  private CompletableFuture<Void> lastFuture;
  private boolean closed;

  private ScriptedBedrockRuntimeAsyncClient(
      List<ConverseStreamOutput> events, Throwable failure, boolean leaveFuturePending) {
    this.events = events;
    this.failure = failure;
    this.leaveFuturePending = leaveFuturePending;
  }

  /** Emits {@code events} in order, then completes the stream's future successfully. */
  static ScriptedBedrockRuntimeAsyncClient succeedingWith(List<ConverseStreamOutput> events) {
    return new ScriptedBedrockRuntimeAsyncClient(events, null, false);
  }

  /**
   * Emits {@code events} in order, then completes the stream's future exceptionally with {@code
   * failure} — the shape of a mid-flight or immediate (empty {@code events}) failure alike.
   */
  static ScriptedBedrockRuntimeAsyncClient failingWith(
      List<ConverseStreamOutput> events, Throwable failure) {
    return new ScriptedBedrockRuntimeAsyncClient(events, failure, false);
  }

  /** Emits {@code events} in order, then leaves the stream's future permanently unresolved. */
  static ScriptedBedrockRuntimeAsyncClient leavingPendingAfter(List<ConverseStreamOutput> events) {
    return new ScriptedBedrockRuntimeAsyncClient(events, null, true);
  }

  @Override
  public CompletableFuture<Void> converseStream(
      ConverseStreamRequest request, ConverseStreamResponseHandler handler) {
    handler.onEventStream(SdkPublisher.fromIterable(events));
    var future = new CompletableFuture<Void>();
    lastFuture = future;
    if (leaveFuturePending) {
      return future;
    }
    if (failure != null) {
      handler.exceptionOccurred(failure);
      future.completeExceptionally(failure);
    } else {
      handler.complete();
      future.complete(null);
    }
    return future;
  }

  /** The future returned by the most recent {@link #converseStream} call. */
  CompletableFuture<Void> lastFuture() {
    return lastFuture;
  }

  @Override
  public String serviceName() {
    return "bedrock-fake";
  }

  @Override
  public void close() {
    closed = true;
  }

  /**
   * Whether {@link #close()} has been called — the close-ownership rider's observable seam: {@link
   * BedrockModelProvider#close()} must call this only for an internally built client, never for one
   * supplied through {@link BedrockModelProvider.Builder#client(BedrockRuntimeAsyncClient)}.
   */
  boolean isClosed() {
    return closed;
  }
}
