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
package org.jwcarman.nessy.examples.governed;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * The org's own sealed intent vocabulary: the only two shapes {@code declare-intent} accepts.
 * Annotated with standard Jackson polymorphism (json-repeal, 2026-08-22) so {@code Schemas} and the
 * tool executor's binding read the same vocabulary.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Restart.class, name = "Restart"),
  @JsonSubTypes.Type(value = Diagnose.class, name = "Diagnose")
})
sealed interface OpsIntent permits Restart, Diagnose {}
