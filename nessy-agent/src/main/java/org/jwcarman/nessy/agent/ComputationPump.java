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
package org.jwcarman.nessy.agent;

import org.jwcarman.continuum.api.BatchSize;
import org.jwcarman.continuum.api.ResultTtl;

/**
 * The six-pump contract {@link ComputationScheduler} drives (continuum-adoption spec §7): deliver,
 * expire, and purge, once each for the approval and tool kinds. {@link DeliveryWorker} is the one
 * production implementation; this seam exists so {@link ComputationScheduler} can be exercised
 * against a hand-written fake in tests without standing up a real worker and its Continuum wiring.
 */
interface ComputationPump {

  /**
   * @param batchSize how many approval deliveries to claim in one pass
   * @return how many deliveries this pass processed
   */
  int drainApprovals(BatchSize batchSize);

  /**
   * @param batchSize how many tool deliveries to claim in one pass
   * @return how many deliveries this pass processed
   */
  int drainTools(BatchSize batchSize);

  /**
   * @param batchSize the maximum expired approvals to process
   * @return the number expired
   */
  int expireApprovals(BatchSize batchSize);

  /**
   * @param batchSize the maximum expired tool computations to process
   * @return the number expired
   */
  int expireTools(BatchSize batchSize);

  /**
   * @param batchSize the maximum result records to delete
   * @param ttl how long results outlive completion
   * @return the number purged
   */
  int purgeApprovals(BatchSize batchSize, ResultTtl ttl);

  /**
   * @param batchSize the maximum result records to delete
   * @param ttl how long results outlive completion
   * @return the number purged
   */
  int purgeTools(BatchSize batchSize, ResultTtl ttl);
}
