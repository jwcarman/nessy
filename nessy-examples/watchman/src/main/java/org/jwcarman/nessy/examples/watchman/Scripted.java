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
package org.jwcarman.nessy.examples.watchman;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.testing.ScriptedModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * {@code --scripted}: a whole round with no API key, no network and no model provider, exactly as
 * {@code hello} offers (spec §4).
 *
 * <p>The script is one honest round in miniature — look at the disk, propose a restart the
 * application will not perform without a human, and write the note every round ends with. Running
 * it is how you find out whether the box's Postgres, the schemas, the page and the projection are
 * wired correctly, without spending a token to find out.
 *
 * <p>A user {@code Model} bean wins over the starter's discovery outright, so activating this
 * profile is the whole switch: nothing else in the application knows the difference.
 */
@Configuration(proxyBeanMethods = false)
@Profile("scripted")
public class Scripted {

  /** The profile name, and the command-line flag that turns it on. */
  public static final String PROFILE = "scripted";

  /** The scripted round. */
  @Bean
  public Model scriptedModel() {
    return ScriptedModel.script(
        s ->
            s.toolUse(
                    "c1",
                    "write_note",
                    JsonNodeFactory.instance
                        .objectNode()
                        .put("text", "rounds done: nothing on fire"))
                .endWithToolUse()
                .text("Rounds complete.")
                .endTurn());
  }
}
