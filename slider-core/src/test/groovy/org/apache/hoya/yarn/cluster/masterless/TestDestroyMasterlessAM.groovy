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

package org.apache.hoya.yarn.cluster.masterless

import groovy.util.logging.Slf4j
import org.apache.hoya.HoyaExitCodes
import org.apache.hoya.exceptions.ErrorStrings
import org.apache.hoya.exceptions.HoyaException
import org.apache.hoya.exceptions.UnknownClusterException
import org.apache.hoya.yarn.Arguments
import org.apache.hoya.yarn.params.CommonArgs
import org.apache.hoya.yarn.client.HoyaClient
import org.apache.hoya.yarn.providers.hbase.HBaseMiniClusterTestBase
import org.apache.hadoop.yarn.service.launcher.ServiceLauncher
import org.junit.Test

/**
 * create masterless AMs and work with them. This is faster than
 * bringing up full clusters
 */
//@CompileStatic
@Slf4j

class TestDestroyMasterlessAM extends HBaseMiniClusterTestBase {

  @Test
  public void testDestroyMasterlessAM() throws Throwable {
    String clustername = "test_destroy_masterless_am"
    createMiniCluster(clustername, getConfiguration(), 1, true)

    describe "create a masterless AM, stop it, try to create" +
             "a second cluster with the same name, destroy it, try a third time"

    ServiceLauncher launcher1 = launchHoyaClientAgainstMiniMR(
        getConfiguration(),
        [
            CommonArgs.ACTION_DESTROY,
            "no-cluster-of-this-name",
            Arguments.ARG_FILESYSTEM, fsDefaultName,
        ])
    assert launcher1.serviceExitCode == 0
    
    ServiceLauncher launcher = createMasterlessAM(clustername, 0, true, true)
    HoyaClient hoyaClient = (HoyaClient) launcher.service
    addToTeardown(hoyaClient);

    clusterActionFreeze(hoyaClient, clustername,"stopping first cluster")
    waitForAppToFinish(hoyaClient)
    

    
    
    //now try to create instance #2, and expect an in-use failure
    try {
      createMasterlessAM(clustername, 0, false, false)
      fail("expected a failure, got an AM")
    } catch (HoyaException e) {
      assertExceptionDetails(e,
                             HoyaExitCodes.EXIT_CLUSTER_EXISTS,
                             ErrorStrings.E_ALREADY_EXISTS)
    }

    
    
    
    //now: destroy it
    int exitCode = hoyaClient.actionDestroy(clustername);
    assert 0 == exitCode
    
    
    //expect thaw to now fail
    try {
      launcher = launch(HoyaClient,
                        configuration,
                        [
                            CommonArgs.ACTION_THAW,
                            clustername,
                            Arguments.ARG_FILESYSTEM, fsDefaultName,
                            Arguments.ARG_MANAGER, RMAddr,
                        ])
      fail("expected an exception")
    } catch (UnknownClusterException e) {
      //expected
    }

    //and create a new cluster
    launcher = createMasterlessAM(clustername, 0, false, false)
    HoyaClient cluster2 = launcher.service as HoyaClient
    
    //try to destroy it while live
    try {
      int ec = cluster2.actionDestroy(clustername)
      fail("expected a failure from the destroy, got error code $ec")
    } catch (HoyaException e) {
      assertFailureClusterInUse(e);
    }
    
    //and try to destroy a completely different cluster just for the fun of it
    assert 0 == hoyaClient.actionDestroy("no-cluster-of-this-name")
  }


}
