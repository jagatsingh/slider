/*
 * Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.hoya;

import org.apache.hadoop.yarn.service.launcher.LauncherExitCodes;

public interface HoyaExitCodes extends LauncherExitCodes {

  /**
   * starting point for exit codes; not an exception itself
   */
  int _EXIT_CODE_BASE =           64;
  
  /**
   * internal error: {@value}
   */
  int EXIT_INTERNAL_ERROR = _EXIT_CODE_BASE;
  
  /**
   * Unimplemented feature: {@value}
   */
  int EXIT_UNIMPLEMENTED =        65;

  /**
   * service entered the failed state: {@value}
   */
  int EXIT_YARN_SERVICE_FAILED =  66;

  /**
   * service was killed: {@value}
   */
  int EXIT_YARN_SERVICE_KILLED =  67;

  /**
   * timeout on monitoring client: {@value}
   */
  int EXIT_TIMED_OUT =            68;

  /**
   * service finished with an error: {@value}
   */
  int EXIT_YARN_SERVICE_FINISHED_WITH_ERROR = 69;

  /**
   * the cluster is unknown: {@value}
   */
  int EXIT_UNKNOWN_HOYA_CLUSTER = 70;

  /**
   * the cluster is in the wrong state for that operation: {@value}
   */
  int EXIT_BAD_CLUSTER_STATE =    71;

  /**
   * A spawned master process failed 
   */
  int EXIT_MASTER_PROCESS_FAILED = 72;

  /**
   * The cluster failed -too many containers were
   * failing or some other threshold was reached
   */
  int EXIT_CLUSTER_FAILED = 73;

  /**
   * The cluster is live -and the requested operation
   * does not work if the cluster is running
   */
  int EXIT_CLUSTER_IN_USE = 74;

  /**
   * There already is a cluster of that name
   * when an attempt is made to create a new cluster
   */
  int EXIT_CLUSTER_EXISTS = 75;

}
