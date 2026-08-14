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
package org.jwcarman.nessy.examples.dispatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;

/**
 * The page-rebuild reading of a {@link Context}: prose only, one {@link Line} per message that has
 * any (spec §3's "transcript lines" bullet).
 *
 * <p>A local copy of {@code chat-web}'s {@code TranscriptView} (spec §3 names it explicitly): a
 * message's {@link TextBlock}s join into one string, in order; every other block kind — thinking,
 * redacted thinking, tool use, tool results — is invisible here, on purpose. A message with no
 * {@code TextBlock}s contributes nothing rather than an empty {@link Line}.
 */
public final class TranscriptView {

  private TranscriptView() {}

  /** One line of the transcript: who said it, and what they said. */
  public record Line(String role, String text) {}

  /** The transcript {@code context} renders as, in message order. */
  public static List<Line> of(Context context) {
    List<Line> lines = new ArrayList<>();
    for (Message message : context.messages()) {
      String text = textOf(message);
      if (!text.isEmpty()) {
        lines.add(new Line(message.role().name().toLowerCase(Locale.ROOT), text));
      }
    }
    return lines;
  }

  private static String textOf(Message message) {
    StringBuilder text = new StringBuilder();
    for (ContentBlock block : message.content()) {
      if (block instanceof TextBlock textBlock) {
        text.append(textBlock.text());
      }
    }
    return text.toString();
  }
}
