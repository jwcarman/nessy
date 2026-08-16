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

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDeltaEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStartEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStopEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamMetadataEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.MessageStartEvent;
import software.amazon.awssdk.services.bedrockruntime.model.MessageStopEvent;

/**
 * The public face of the Bedrock provider module: turns a {@link ModelRequest} into a live
 * streaming {@code ConverseStream} call against Amazon Bedrock via the AWS SDK for Java v2's {@code
 * bedrockruntime} client.
 *
 * <p>Everything upstream of this class is pure translation ({@link BedrockRequests}, {@link
 * BedrockStream}); this class is the one place that owns a {@link BedrockClient} and actually talks
 * to the network — including the async-to-blocking bridge {@link Builder#wrap} builds, since the
 * SDK's {@code converseStream} is a push-callback API and {@link ModelStream} is a blocking {@code
 * Iterable} (see {@link BedrockClient}'s class javadoc).
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
 * <p>Also {@link AutoCloseable}: the real {@link BedrockClient} built by {@link Builder#wrap} owns
 * a {@code BedrockRuntimeAsyncClient}, whose default Netty transport holds an event-loop group and
 * connection pool that outlive a single {@link #stream} call. {@link ModelProvider} itself declares
 * no {@code close()} — most sibling providers wrap a client with no such teardown need — so this is
 * additive: callers that construct a {@code BedrockModelProvider} directly (rather than through a
 * DI container that already manages its lifecycle) should close it when done, the same as they
 * would the underlying SDK client itself.
 *
 * <p><b>Close ownership is not symmetric across {@link Builder}'s two client paths.</b> {@link
 * #close()} closes the {@code BedrockRuntimeAsyncClient} only when this provider built it itself
 * (the {@code region}/{@code credentialsProvider}/{@code fromEnv()} path); a client handed in
 * through {@link Builder#client(BedrockRuntimeAsyncClient)} is never closed here — see that
 * method's javadoc. The two paths are not independent alternatives for who does the closing, only
 * for who does the building.
 */
public final class BedrockModelProvider implements ModelProvider, AutoCloseable {

  private static final Set<Capability> CAPABILITIES = Set.of(Capability.PARALLEL_TOOL_CALLS);

  private final BedrockClient client;

  BedrockModelProvider(BedrockClient client) {
    this.client = client;
  }

  public static Builder builder() {
    return new Builder();
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

  /** Assembles a {@link BedrockModelProvider}. */
  public static final class Builder {

    private static final String AWS_REGION_ENV_VAR = "AWS_REGION";
    private static final String AWS_DEFAULT_REGION_ENV_VAR = "AWS_DEFAULT_REGION";

    private Region region;
    private AwsCredentialsProvider credentialsProvider;
    private BedrockRuntimeAsyncClient sdkClient;
    private boolean useEnv;

    private Builder() {}

    /** The AWS region Bedrock requests are sent to. */
    public Builder region(Region region) {
      this.region = region;
      return this;
    }

    /** Overrides the default AWS credentials provider chain. */
    public Builder credentialsProvider(AwsCredentialsProvider credentialsProvider) {
      this.credentialsProvider = credentialsProvider;
      return this;
    }

    /**
     * Uses the AWS SDK's own default credentials provider chain, and resolves the region by reading
     * {@value #AWS_REGION_ENV_VAR} then, if that is unset, {@value #AWS_DEFAULT_REGION_ENV_VAR}
     * itself, rather than delegating to the SDK's own default region provider chain — the
     * seam-integrity rule the sibling provider modules established: the choice this builder makes
     * from the environment is the choice that gets built, not a second, independent read underneath
     * it.
     *
     * <p>Only a flag is set here; nothing is read yet. {@link #build()} applies it, layering any
     * explicit {@link #region(Region)} set on <em>this</em> builder on top so it wins over either
     * environment variable.
     *
     * @throws IllegalStateException at {@link #build()} time if neither an explicit region nor
     *     either environment variable is available
     */
    public Builder fromEnv() {
      this.useEnv = true;
      return this;
    }

    /**
     * Escape hatch: supply a fully preconfigured {@link BedrockRuntimeAsyncClient} instead of
     * {@code region}/{@code credentialsProvider}.
     *
     * <p><b>Ownership stays with the caller.</b> {@link BedrockModelProvider#close()} closes only a
     * client this builder constructed itself from {@code region}/{@code credentialsProvider}/{@code
     * fromEnv()} — a client supplied here is never closed by the provider, since it was never
     * opened by the provider either. Close {@code client} yourself, on whatever lifecycle you built
     * it against.
     */
    public Builder client(BedrockRuntimeAsyncClient client) {
      this.sdkClient = client;
      return this;
    }

    public BedrockModelProvider build() {
      return new BedrockModelProvider(resolveClient());
    }

    private BedrockClient resolveClient() {
      if (sdkClient != null) {
        return wrap(sdkClient, false);
      }
      var resolvedRegion = resolveRegion();
      var resolvedCredentials =
          credentialsProvider != null ? credentialsProvider : DefaultCredentialsProvider.create();
      var asyncClient =
          BedrockRuntimeAsyncClient.builder()
              .region(resolvedRegion)
              .credentialsProvider(resolvedCredentials)
              .build();
      return wrap(asyncClient, true);
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
      throw missingRegion();
    }

    private static IllegalStateException missingRegion() {
      var message =
          AWS_REGION_ENV_VAR
              + " (or "
              + AWS_DEFAULT_REGION_ENV_VAR
              + ") environment variable is not set; call region(...) or fromEnv(), or provide a"
              + " preconfigured client via client(...)";
      return new IllegalStateException(message);
    }

    /**
     * Bridges the SDK's push-based {@code converseStream(request, responseHandler)} into the
     * pull-shaped, blocking {@link BedrockStream} the rest of this module expects — see {@link
     * BedrockClient}'s class javadoc for why the bridge is necessary. A visitor pushes every raw
     * {@link ConverseStreamOutput} onto a queue as it arrives on the SDK's own thread; the blocking
     * {@link BridgeIterator} this method hands to {@link BedrockStream} pulls from that same queue
     * on the caller's thread. The queue also carries the stream's own end-of-stream signals —
     * {@link #DONE} on success, a {@link StreamFailure} on error — so a caller iterating the
     * resulting {@link BedrockStream} sees a normal end of iteration or a thrown exception, never a
     * silently truncated stream.
     *
     * <p>The queue is deliberately unbounded ({@link LinkedBlockingQueue}'s no-capacity
     * constructor): the producer side runs on the SDK's own Netty event-loop thread, and blocking
     * an event loop on a full queue would stall every other request multiplexed over that loop —
     * far worse than letting one turn's events buffer in memory. {@code maxTokens} already bounds
     * how much one turn can produce, so the buffer is bounded in practice even though the queue
     * itself is not.
     *
     * <p><b>Priming the pump.</b> This method blocks for the stream's first queue item — translated
     * event, {@link #DONE}, or {@link StreamFailure} — before returning, so a failure on the very
     * first call (a 429 throttle, an expired credential, a guardrail-blocked request) throws from
     * {@link BedrockModelProvider#stream} itself rather than only once the caller starts iterating.
     * This matters: {@code RetryingModelProvider} retries only the opening of a stream (its
     * javadoc: "once events flow, tokens have already been fed downstream"), so an opening failure
     * that instead surfaced from the first {@code hasNext()}/{@code next()} call would silently
     * never be retried, unlike the identical failure on every synchronous-SDK sibling provider.
     *
     * <p><b>Close ownership.</b> {@code ownsClient} decides what {@link BedrockClient#close()} does
     * to {@code sdkClient}: {@code true} for the client this builder constructed itself (the {@code
     * region}/{@code credentialsProvider} path in {@link #resolveClient()}), {@code false} for one
     * handed in through {@link #client(BedrockRuntimeAsyncClient)}. A caller who supplied their own
     * {@code BedrockRuntimeAsyncClient} still owns it — {@link BedrockModelProvider#close()} must
     * never close resources it did not open, the same convention a caller-supplied {@code
     * DataSource} or {@code ExecutorService} follows elsewhere. Package-private rather than {@code
     * private} so the ownership branch is directly testable without a real, network-capable SDK
     * client on either side (see {@code BedrockModelProviderTest$CloseOwnership}).
     */
    static BedrockClient wrap(BedrockRuntimeAsyncClient sdkClient, boolean ownsClient) {
      return new BedrockClient() {
        @Override
        public BedrockStream converseStream(ConverseStreamRequest request) {
          BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
          var handler =
              ConverseStreamResponseHandler.builder()
                  .subscriber(new QueueingVisitor(queue))
                  .build();
          var future = sdkClient.converseStream(request, handler);
          future.whenComplete(
              (ignored, error) -> queue.add(error == null ? DONE : new StreamFailure(error)));
          Object first = take(queue);
          if (first instanceof StreamFailure failure) {
            throw failure.toRuntimeException();
          }
          Iterable<ConverseStreamOutput> bridge = () -> new BridgeIterator(queue, first);
          return new BedrockStream(bridge, () -> future.cancel(true));
        }

        @Override
        public void close() {
          if (ownsClient) {
            sdkClient.close();
          }
        }
      };
    }
  }

  /** Sentinel enqueued once the SDK's stream future completes successfully. */
  private static final Object DONE = new Object();

  /** Enqueued once the SDK's stream future completes exceptionally. */
  private record StreamFailure(Throwable cause) {

    /**
     * Unwraps a {@link CompletionException} the SDK's own future chaining may have wrapped the real
     * failure in, and rethrows the underlying cause directly when it is already a {@link
     * RuntimeException} — an {@code SdkServiceException} (throttling, guardrail, auth, …) always is
     * — so the reason that lands in the durable transcript ({@code ProviderModelCallExecutor}'s
     * {@code e.getClass().getSimpleName() + ": " + e.getMessage()}) names the provider's own
     * diagnosis instead of a generic wrapper every Bedrock failure would otherwise collapse into
     * identically.
     */
    RuntimeException toRuntimeException() {
      Throwable actual =
          cause instanceof CompletionException && cause.getCause() != null
              ? cause.getCause()
              : cause;
      if (actual instanceof RuntimeException runtimeException) {
        return runtimeException;
      }
      return new IllegalStateException(
          actual.getClass().getSimpleName() + ": " + actual.getMessage(), actual);
    }
  }

  /**
   * Pushes every {@code ConverseStreamOutput} the SDK delivers onto a blocking queue, keeping this
   * harness's translation ({@link BedrockStream}) entirely off the SDK's own callback threads.
   */
  private static final class QueueingVisitor implements ConverseStreamResponseHandler.Visitor {

    private final BlockingQueue<Object> queue;

    QueueingVisitor(BlockingQueue<Object> queue) {
      this.queue = queue;
    }

    @Override
    public void visitMessageStart(MessageStartEvent event) {
      offer(event);
    }

    @Override
    public void visitContentBlockStart(ContentBlockStartEvent event) {
      offer(event);
    }

    @Override
    public void visitContentBlockDelta(ContentBlockDeltaEvent event) {
      offer(event);
    }

    @Override
    public void visitContentBlockStop(ContentBlockStopEvent event) {
      offer(event);
    }

    @Override
    public void visitMessageStop(MessageStopEvent event) {
      offer(event);
    }

    @Override
    public void visitMetadata(ConverseStreamMetadataEvent event) {
      offer(event);
    }

    /**
     * The queue is unbounded (see {@link Builder#wrap}'s javadoc), so {@link
     * BlockingQueue#add(Object)} never actually blocks here — it says that plainly, unlike a {@code
     * put} that would need interrupt handling it can never hit in practice.
     */
    private void offer(ConverseStreamOutput event) {
      queue.add(event);
    }
  }

  /**
   * Pulls from the bridging queue on the caller's thread, translating the queue's two sentinel
   * shapes ({@link #DONE}, {@link StreamFailure}) into ordinary {@link Iterator} termination: a
   * clean end of iteration, or a thrown {@link IllegalStateException} wrapping the SDK's failure.
   *
   * <p>Constructed with the stream's already-taken first item ({@link Builder#wrap}'s pump-priming
   * read) so that item is not lost — it becomes this iterator's initial lookahead (or marks it
   * already finished, if the very first item was {@link #DONE}) rather than being re-fetched from
   * the queue.
   */
  private static final class BridgeIterator implements Iterator<ConverseStreamOutput> {

    private final BlockingQueue<Object> queue;
    private ConverseStreamOutput lookahead;
    private boolean finished;

    private BridgeIterator(BlockingQueue<Object> queue, Object primedFirst) {
      this.queue = queue;
      if (primedFirst == DONE) {
        finished = true;
      } else {
        lookahead = (ConverseStreamOutput) primedFirst;
      }
    }

    @Override
    public boolean hasNext() {
      fill();
      return !finished;
    }

    @Override
    public ConverseStreamOutput next() {
      fill();
      if (finished) {
        throw new NoSuchElementException();
      }
      var result = lookahead;
      lookahead = null;
      return result;
    }

    private void fill() {
      if (lookahead != null || finished) {
        return;
      }
      Object item = take(queue);
      if (item == DONE) {
        finished = true;
      } else if (item instanceof StreamFailure failure) {
        finished = true;
        throw failure.toRuntimeException();
      } else {
        lookahead = (ConverseStreamOutput) item;
      }
    }
  }

  /**
   * Blocks for the next queue item, restoring the interrupt flag and failing loudly rather than
   * swallowing cancellation — the same precedent {@code Sleeper.REAL} sets for this repository's
   * only other blocking wait. A container shutdown or a request-timeout filter that interrupts the
   * thread folding a Bedrock turn must unwind that thread, not have its interrupt silently consumed
   * while this method keeps waiting for a queue element that may never arrive.
   */
  private static Object take(BlockingQueue<Object> queue) {
    try {
      return queue.take();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while streaming from Bedrock", e);
    }
  }
}
