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
module org.jwcarman.nessy.core {
  requires transitive com.fasterxml.jackson.databind;
  requires com.fasterxml.jackson.annotation;
  requires transitive micrometer.observation;
  requires com.github.victools.jsonschema.generator;
  requires com.github.victools.jsonschema.module.jackson;
  requires com.fasterxml.classmate;

  exports org.jwcarman.nessy;
  exports org.jwcarman.nessy.api;
  exports org.jwcarman.nessy.api.tool;
  exports org.jwcarman.nessy.api.approval;
  exports org.jwcarman.nessy.api.event;
  exports org.jwcarman.nessy.spi;
  exports org.jwcarman.nessy.spi.model;
  exports org.jwcarman.nessy.spi.session;
// org.jwcarman.nessy.internal is deliberately NOT exported.
}
