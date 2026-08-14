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

/**
 * This module ({@code nessy-spring-boot-starter}) is a src-less dependency aggregator — see the
 * module's {@code README}/{@code pom.xml} description — so it ships no real Java of its own; every
 * class an application actually uses comes from {@code nessy-autoconfigure} and {@code nessy-core}.
 *
 * <p>This placeholder package (and {@link Placeholder}) exists only so {@code
 * maven-javadoc-plugin}'s release-profile execution (see {@code pom.xml}'s {@code <sourcepath>}
 * override) has at least one real public type to document: without one, the plugin either skips
 * producing a {@code -javadoc.jar} entirely or fails outright with "No public or protected classes
 * found to document," and Maven Central Portal validation rejects a non-pom artifact missing either
 * that jar or its {@code -sources.jar} sibling.
 */
package org.jwcarman.nessy.springbootstarter;
