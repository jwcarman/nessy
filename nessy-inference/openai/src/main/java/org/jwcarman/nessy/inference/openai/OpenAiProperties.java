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
package org.jwcarman.nessy.inference.openai;

import java.util.List;
import org.jwcarman.nessy.api.VendorProperty;

/**
 * The {@code openai.} vendor properties both OpenAI wires support, declared once: set one with
 * {@code property(OpenAiProperties.REASONING_EFFORT, OpenAiReasoningEffort.HIGH)}, or by name and
 * text, as YAML does. A name under the prefix that is not one of them is ignored, and said so once
 * when it is checked.
 */
public final class OpenAiProperties {

  /** How hard a reasoning model thinks. */
  public static final VendorProperty<OpenAiReasoningEffort> REASONING_EFFORT =
      VendorProperty.ofEnum(
          "openai.reasoning.effort", OpenAiReasoningEffort.class, OpenAiReasoningEffort::spelling);

  /** How much of its reasoning a model summarises. The openai-responses wire only. */
  public static final VendorProperty<OpenAiReasoningSummary> REASONING_SUMMARY =
      VendorProperty.ofEnum(
          "openai.reasoning.summary",
          OpenAiReasoningSummary.class,
          OpenAiReasoningSummary::spelling);

  /**
   * Whether function tools go out strict. The openai-responses wire always sends them strict, so it
   * refuses {@code false}.
   */
  public static final VendorProperty<Boolean> TOOLS_STRICT =
      VendorProperty.ofBoolean("openai.tools.strict");

  /** The processing tier a request asks for. */
  public static final VendorProperty<OpenAiServiceTier> SERVICE_TIER =
      VendorProperty.ofEnum(
          "openai.service_tier", OpenAiServiceTier.class, OpenAiServiceTier::spelling);

  /** Every property this adapter supports. */
  public static final List<VendorProperty<?>> SUPPORTED =
      List.of(REASONING_EFFORT, REASONING_SUMMARY, TOOLS_STRICT, SERVICE_TIER);

  private OpenAiProperties() {}
}
