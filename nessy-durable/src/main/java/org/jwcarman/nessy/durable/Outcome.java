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
package org.jwcarman.nessy.durable;

import java.util.Objects;

/**
 * What the work came to, as a value (durable spec §8). Infrastructure failures are exceptions; the
 * work's own failure is an {@code Outcome}.
 */
public sealed interface Outcome {

  record Success(Object value) implements Outcome {
    public Success {
      Objects.requireNonNull(value, "value must not be null");
    }
  }

  record Failure(String message) implements Outcome {
    public Failure {
      Objects.requireNonNull(message, "message must not be null");
    }
  }

  record Cancelled(String reason) implements Outcome {
    public Cancelled {
      Objects.requireNonNull(reason, "reason must not be null");
    }
  }
}
