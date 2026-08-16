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

import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;

/**
 * The seam between {@link BedrockModelProvider} and the AWS SDK's {@code converseStream} call.
 *
 * <p>Unlike the anthropic-java, openai-java, and java-genai SDKs — each of which offers some
 * synchronous, pull-shaped streaming entry point this harness can iterate directly — the AWS SDK
 * for Java v2 streams {@code ConverseStream} only through {@code BedrockRuntimeAsyncClient}, whose
 * {@code converseStream(request, responseHandler)} is push-based: the caller hands over a {@code
 * ConverseStreamResponseHandler} and the SDK invokes its visitor callbacks on its own threads as
 * events arrive, completing a {@code CompletableFuture<Void>} when the stream ends. {@link
 * ModelStream}, by contrast, is a blocking {@code Iterable} — the shape every other provider module
 * in this harness already fits. Production code ({@link BedrockModelProvider.Builder}) bridges the
 * two: it registers a visitor that pushes each raw {@code ConverseStreamOutput} onto a blocking
 * queue and hands back a pull-shaped {@code Iterable} that {@link BedrockStream} can iterate
 * exactly like {@code GeminiStream} iterates a plain {@code List} of SDK response chunks.
 *
 * <p>This package-private interface is the thin seam that isolates that bridging from the rest of
 * the module: tests implement it directly with a hand-rolled fake that returns a {@link
 * BedrockStream} built from plain SDK response fixtures — no async client, no mocking library, no
 * SDK internals touched.
 */
interface BedrockClient {

  /** Starts one streaming {@code ConverseStream} call. */
  BedrockStream converseStream(ConverseStreamRequest request);
}
