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

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Open. No login, no user store, no password.
 *
 * <p>It had one user and a form in front of everything, and the password was {@code watchman} in a
 * committed file — ceremony that stopped nobody while making the page awkward to reach. A demo that
 * asks for a credential it publishes is not demonstrating access control; it is demonstrating a
 * login form.
 *
 * <p><b>Read this before copying it.</b> The approvals page is where a person turns a proposal into
 * a real command on the host — {@code prune_images}, a service restart. Unauthenticated, the answer
 * to "is a person willing to allow this?" becomes "whoever reached the port first". That is fine
 * for a laptop and wrong for anything reachable by a network you do not control. If you run this
 * somewhere real, put a credential back or bind it to localhost, and do not do both by halves.
 *
 * <p>Answers are recorded as "someone" now, because there is no longer anyone to name.
 */
@Configuration
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    // Spring Security is still on the classpath, so a chain is still required: without one the
    // defaults apply, and the defaults are basic auth with a password printed to the log.
    http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
        .anonymous(anonymous -> anonymous.disable())
        .csrf(csrf -> csrf.disable());
    return http.build();
  }
}
