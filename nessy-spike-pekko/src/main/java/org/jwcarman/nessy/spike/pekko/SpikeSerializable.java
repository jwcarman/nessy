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
package org.jwcarman.nessy.spike.pekko;

/**
 * THROWAWAY SPIKE. Marker for everything Pekko may put on the wire or in the durable-state store.
 *
 * <p>Pekko binds serializers to types by configuration, not by annotation, so one marker interface
 * is the whole registration: {@code spike-common.conf} binds this interface to {@code jackson-json}
 * and every command and every state record inherits that binding.
 *
 * <p>JSON rather than CBOR on purpose — the spike wants the stored bytes to be readable straight
 * out of the store.
 */
public interface SpikeSerializable {}
