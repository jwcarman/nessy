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
package org.jwcarman.nessy.model.gemini;

import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import java.util.List;

/**
 * The seam between {@link GeminiModelProvider} and the java-genai SDK's streaming call.
 *
 * <p>Unlike the anthropic-java and openai-java SDKs, whose client entry points are interfaces a JDK
 * dynamic proxy can stand in for, the java-genai SDK's {@code Client} and {@code Models} are both
 * {@code final} classes, and the {@code ResponseStream} they return can only be constructed through
 * a real {@code ApiResponse} plus a reflection-resolved converter method — none of it
 * offline-constructible. This package-private interface is the thin seam that sidesteps all of
 * that: production code ({@link GeminiProviderConfig}) wraps the real SDK call behind it; tests
 * implement it directly with a hand-rolled fake that returns a {@link GeminiStream} built from
 * plain SDK response fixtures — no mocking library, no SDK internals touched.
 */
interface GeminiClient {

  /** Starts one streaming {@code generateContent} call. */
  GeminiStream generateContentStream(
      String model, List<Content> contents, GenerateContentConfig config);
}
