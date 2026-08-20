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
package org.jwcarman.nessy.agent.narrate;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;

/**
 * The synchronous adapter (§7): one observer, one future, no second code path. The caller's thread
 * parks on the future while executor threads do the work; a parked or slow turn is just a timeout,
 * uniform with every other slow tool.
 */
public final class AwaitingReply implements TurnObserver {

  private final CompletableFuture<String> reply = new CompletableFuture<>();
  private volatile String lastAssistantText = "";

  @Override
  public void on(TurnEvent event) {
    switch (event) {
      case TurnEvent.AssistantSaid said -> lastAssistantText = textOf(said);
      case TurnEvent.TurnEnded ended -> {
        if (ended.failed()) {
          reply.completeExceptionally(
              new IllegalStateException("turn failed: " + ended.failureReason()));
        } else {
          reply.complete(lastAssistantText);
        }
      }
      default -> {}
    }
  }

  /** True once the reply has settled — completed, failed, or the caller stopped waiting. */
  public boolean isDone() {
    return reply.isDone();
  }

  public String await(Duration timeout) {
    try {
      return reply.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new IllegalStateException("turn timed out after " + timeout, e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException cause) {
        throw cause;
      }
      throw new IllegalStateException("turn failed", e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted awaiting the turn", e);
    }
  }

  private static String textOf(TurnEvent.AssistantSaid said) {
    StringBuilder joined = new StringBuilder();
    for (ContentBlock block : said.message().content()) {
      if (block instanceof TextBlock(String text)) {
        joined.append(text);
      }
    }
    return joined.toString();
  }
}
