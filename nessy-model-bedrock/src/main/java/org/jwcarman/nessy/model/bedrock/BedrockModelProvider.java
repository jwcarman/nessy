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
 * calls unconditionally; the task brief's "TOOLS + PARALLEL_TOOL_CALLS" phrasing is honored as best
 * this enum allows. {@link Capability#THINKING}, {@link Capability#PROMPT_CACHING}, and {@link
 * Capability#IMAGE_INPUT} are deliberately absent: none is wired into this module's
 * request/response mapping, so none is claimed — the same discipline {@code GeminiModelProvider}
 * documents for its own unadvertised capabilities.
 */
public final class BedrockModelProvider implements ModelProvider {

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
        return wrap(sdkClient);
      }
      var resolvedRegion = resolveRegion();
      var resolvedCredentials =
          credentialsProvider != null ? credentialsProvider : DefaultCredentialsProvider.create();
      var asyncClient =
          BedrockRuntimeAsyncClient.builder()
              .region(resolvedRegion)
              .credentialsProvider(resolvedCredentials)
              .build();
      return wrap(asyncClient);
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
     */
    private static BedrockClient wrap(BedrockRuntimeAsyncClient sdkClient) {
      return request -> {
        BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        var handler =
            ConverseStreamResponseHandler.builder().subscriber(new QueueingVisitor(queue)).build();
        var future = sdkClient.converseStream(request, handler);
        future.whenComplete(
            (ignored, error) ->
                putUninterruptibly(queue, error == null ? DONE : new StreamFailure(error)));
        Iterable<ConverseStreamOutput> bridge = () -> new BridgeIterator(queue);
        return new BedrockStream(bridge, () -> future.cancel(true));
      };
    }
  }

  /** Sentinel enqueued once the SDK's stream future completes successfully. */
  private static final Object DONE = new Object();

  /** Enqueued once the SDK's stream future completes exceptionally. */
  private record StreamFailure(Throwable cause) {

    RuntimeException toRuntimeException() {
      return new IllegalStateException("Bedrock ConverseStream failed", cause);
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

    private void offer(ConverseStreamOutput event) {
      putUninterruptibly(queue, event);
    }
  }

  /**
   * Pulls from the bridging queue on the caller's thread, translating the queue's two sentinel
   * shapes ({@link #DONE}, {@link StreamFailure}) into ordinary {@link Iterator} termination: a
   * clean end of iteration, or a thrown {@link IllegalStateException} wrapping the SDK's failure.
   */
  private static final class BridgeIterator implements Iterator<ConverseStreamOutput> {

    private final BlockingQueue<Object> queue;
    private ConverseStreamOutput lookahead;
    private boolean finished;

    private BridgeIterator(BlockingQueue<Object> queue) {
      this.queue = queue;
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
      Object item = takeUninterruptibly(queue);
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

  private static void putUninterruptibly(BlockingQueue<Object> queue, Object item) {
    boolean interrupted = false;
    try {
      while (true) {
        try {
          queue.put(item);
          return;
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static Object takeUninterruptibly(BlockingQueue<Object> queue) {
    boolean interrupted = false;
    try {
      while (true) {
        try {
          return queue.take();
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
