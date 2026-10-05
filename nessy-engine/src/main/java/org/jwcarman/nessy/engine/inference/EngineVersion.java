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
package org.jwcarman.nessy.engine.inference;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The version of this engine, read once from a resource that the build fills in. A resource that is
 * missing, or has no version, or still holds the placeholder, is a fault at first use: a version of
 * "unknown" written into every stored request would be worse than a failure.
 */
public final class EngineVersion {

  private static final String RESOURCE = "/org/jwcarman/nessy/engine/version.properties";
  private static final String PLACEHOLDER_MARK = "${";

  private static volatile String version;

  private EngineVersion() {}

  /** The version of this engine, such as {@code 0.5.0-SNAPSHOT}. */
  public static String current() {
    String known = version;
    if (known == null) {
      // A fault is thrown as itself each time it is asked; only a good read is kept.
      known = read();
      version = known;
    }
    return known;
  }

  private static String read() {
    try (InputStream in = EngineVersion.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("the engine's version resource is missing: " + RESOURCE);
      }
      Properties properties = new Properties();
      properties.load(in);
      String found = properties.getProperty("version");
      if (found == null || found.isBlank()) {
        throw new IllegalStateException("the engine's version resource has no version");
      }
      if (found.contains(PLACEHOLDER_MARK)) {
        throw new IllegalStateException(
            "the engine's version resource was not filled in by the build: " + found);
      }
      return found.strip();
    } catch (IOException e) {
      throw new IllegalStateException("the engine's version resource could not be read", e);
    }
  }
}
