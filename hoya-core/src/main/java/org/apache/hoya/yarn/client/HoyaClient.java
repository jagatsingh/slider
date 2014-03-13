/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.hoya.yarn.client;

import com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;
import org.apache.hadoop.yarn.client.api.YarnClientApplication;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.service.launcher.RunService;
import org.apache.hadoop.yarn.util.Records;
import org.apache.hoya.HoyaExitCodes;
import org.apache.hoya.HoyaKeys;
import org.apache.hoya.HoyaXmlConfKeys;
import org.apache.hoya.api.ClusterDescription;
import org.apache.hoya.api.ClusterNode;
import org.apache.hoya.api.HoyaClusterProtocol;
import org.apache.hoya.api.OptionKeys;
import org.apache.hoya.api.RoleKeys;
import org.apache.hoya.api.StatusKeys;
import org.apache.hoya.api.proto.Messages;
import org.apache.hoya.core.build.InstanceBuilder;
import org.apache.hoya.core.conf.AggregateConf;
import org.apache.hoya.core.conf.ConfTree;
import org.apache.hoya.core.conf.ConfTreeOperations;
import org.apache.hoya.core.persist.LockAcquireFailedException;
import org.apache.hoya.core.registry.ServiceRegistryClient;
import org.apache.hoya.exceptions.BadClusterStateException;
import org.apache.hoya.exceptions.BadCommandArgumentsException;
import org.apache.hoya.exceptions.BadConfigException;
import org.apache.hoya.exceptions.ErrorStrings;
import org.apache.hoya.exceptions.HoyaException;
import org.apache.hoya.exceptions.NoSuchNodeException;
import org.apache.hoya.exceptions.UnknownClusterException;
import org.apache.hoya.exceptions.WaitTimeoutException;
import org.apache.hoya.providers.AbstractClientProvider;
import org.apache.hoya.providers.HoyaProviderFactory;
import org.apache.hoya.providers.ProviderRole;
import org.apache.hoya.providers.hoyaam.HoyaAMClientProvider;
import org.apache.hoya.tools.ConfigHelper;
import org.apache.hoya.tools.Duration;
import org.apache.hoya.tools.HoyaFileSystem;
import org.apache.hoya.tools.HoyaUtils;
import org.apache.hoya.tools.HoyaVersionInfo;
import org.apache.hoya.yarn.Arguments;
import org.apache.hoya.yarn.HoyaActions;
import org.apache.hoya.yarn.appmaster.rpc.RpcBinder;
import org.apache.hoya.yarn.params.AbstractClusterBuildingActionArgs;
import org.apache.hoya.yarn.params.ActionAMSuicideArgs;
import org.apache.hoya.yarn.params.ActionCreateArgs;
import org.apache.hoya.yarn.params.ActionEchoArgs;
import org.apache.hoya.yarn.params.ActionFlexArgs;
import org.apache.hoya.yarn.params.ActionFreezeArgs;
import org.apache.hoya.yarn.params.ActionGetConfArgs;
import org.apache.hoya.yarn.params.ActionKillContainerArgs;
import org.apache.hoya.yarn.params.ActionThawArgs;
import org.apache.hoya.yarn.params.ClientArgs;
import org.apache.hoya.yarn.params.HoyaAMArgs;
import org.apache.hoya.yarn.params.LaunchArgsAccessor;
import org.apache.hoya.yarn.service.CompoundLaunchedService;
import org.apache.hoya.yarn.service.HoyaServiceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Client service for Hoya
 */

public class HoyaClient extends CompoundLaunchedService implements RunService,
                                                          HoyaExitCodes,
                                                          HoyaKeys,
                                                          ErrorStrings {
  private static final Logger log = LoggerFactory.getLogger(HoyaClient.class);

  public static final int ACCEPT_TIME = 60000;
  public static final int CONNECT_TIMEOUT = 10000;
  public static final int RPC_TIMEOUT = 15000;

  private ClientArgs serviceArgs;
  public ApplicationId applicationId;
  

  private String deployedClusterName;
  /**
   * Cluster opaerations against the deployed cluster -will be null
   * if no bonding has yet taken place
   */
  private HoyaClusterOperations hoyaClusterOperations;

  private HoyaFileSystem hoyaFileSystem;

  /**
   * Yarn client service
   */
  private HoyaYarnClientImpl yarnClient;
  private ServiceRegistryClient serviceRegistryClient;

  /**
   * Constructor
   */
  public HoyaClient() {
    // make sure all the yarn configs get loaded
    YarnConfiguration yarnConfiguration = new YarnConfiguration();
    log.debug("Hoya constructed");
  }

  @Override
  // Service
  public String getName() {
    return "Hoya";
  }

  @Override
  public Configuration bindArgs(Configuration config, String... args) throws Exception {
    config = super.bindArgs(config, args);
    log.debug("Binding Arguments");
    serviceArgs = new ClientArgs(args);
    serviceArgs.parse();
    // yarn-ify
    YarnConfiguration yarnConfiguration = new YarnConfiguration(config);
    return HoyaUtils.patchConfiguration(yarnConfiguration);
  }

  @Override
  protected void serviceInit(Configuration conf) throws Exception {
    Configuration clientConf = HoyaUtils.loadHoyaClientConfigurationResource();
    ConfigHelper.mergeConfigurations(conf, clientConf, HOYA_CLIENT_RESOURCE);
    serviceArgs.applyDefinitions(conf);
    serviceArgs.applyFileSystemURL(conf);
    // init security with our conf
    if (HoyaUtils.isClusterSecure(conf)) {
      HoyaUtils.forceLogin();
      HoyaUtils.initProcessSecurity(conf);
    }
    //create the YARN client
    yarnClient = new HoyaYarnClientImpl();
    addService(yarnClient);

    
    
    super.serviceInit(conf);
    
    //here the superclass is inited; getConfig returns a non-null value
    hoyaFileSystem = new HoyaFileSystem(conf);
    serviceRegistryClient =
      new ServiceRegistryClient(yarnClient, getUsername(), conf);
  }

  /**
   * this is where the work is done.
   * @return the exit code
   * @throws Throwable anything that went wrong
   */
  @Override
  public int runService() throws Throwable {

    // choose the action
    String action = serviceArgs.getAction();
    int exitCode = EXIT_SUCCESS;
    String clusterName = serviceArgs.getClusterName();
    // actions
    if (HoyaActions.ACTION_BUILD.equals(action)) {
      exitCode = actionBuild(clusterName, serviceArgs.getActionBuildArgs());
    } else if (HoyaActions.ACTION_CREATE.equals(action)) {
      exitCode = actionCreate(clusterName, serviceArgs.getActionCreateArgs());
    } else if (HoyaActions.ACTION_FREEZE.equals(action)) {
      exitCode = actionFreeze(clusterName,
                              serviceArgs.getActionFreezeArgs());
    } else if (HoyaActions.ACTION_THAW.equals(action)) {
      exitCode = actionThaw(clusterName, serviceArgs.getActionThawArgs());
    } else if (HoyaActions.ACTION_DESTROY.equals(action)) {
      HoyaUtils.validateClusterName(clusterName);
      exitCode = actionDestroy(clusterName);
    } else if (HoyaActions.ACTION_EMERGENCY_FORCE_KILL.equals(action)) {
      exitCode = actionEmergencyForceKill(clusterName);
    } else if (HoyaActions.ACTION_EXISTS.equals(action)) {
      HoyaUtils.validateClusterName(clusterName);
      exitCode = actionExists(clusterName,
                              serviceArgs.getActionExistsArgs().live);
    } else if (HoyaActions.ACTION_FLEX.equals(action)) {
      HoyaUtils.validateClusterName(clusterName);
      exitCode = actionFlex(clusterName, serviceArgs.getActionFlexArgs());
    } else if (HoyaActions.ACTION_GETCONF.equals(action)) {
      exitCode = actionGetConf(clusterName, serviceArgs.getActionGetConfArgs());
    } else if (HoyaActions.ACTION_HELP.equals(action) ||
               HoyaActions.ACTION_USAGE.equals(action)) {
      log.info("HoyaClient {}", serviceArgs.usage());

    } else if (HoyaActions.ACTION_KILL_CONTAINER.equals(action)) {
      exitCode = actionGetConf(clusterName, serviceArgs.getActionGetConfArgs());

    } else if (HoyaActions.ACTION_AM_SUICIDE.equals(action)) {
      exitCode = actionAmSuicide(clusterName,
                                 serviceArgs.getActionAMSuicideArgs());

    } else if (HoyaActions.ACTION_LIST.equals(action)) {
      if (!isUnset(clusterName)) {
        HoyaUtils.validateClusterName(clusterName);
      }
      exitCode = actionList(clusterName);
    } else if (HoyaActions.ACTION_STATUS.equals(action)) {
      
      exitCode = actionStatus(clusterName,
                              serviceArgs.getActionStatusArgs().getOutput());
    } else if (HoyaActions.ACTION_VERSION.equals(action)) {
      
      exitCode = actionVersion();
    } else {
      throw new HoyaException(EXIT_UNIMPLEMENTED,
                              "Unimplemented: " + action);
    }

    return exitCode;
  }


  /**
   * Destroy a cluster. There's two race conditions here
   * #1 the cluster is started between verifying that there are no live
   * clusters of that name.
   */
  public int actionDestroy(String clustername) throws YarnException,
                                                      IOException {
    // verify that a live cluster isn't there
    HoyaUtils.validateClusterName(clustername);
    //no=op, it is now mandatory. 
    verifyManagerSet();
    verifyNoLiveClusters(clustername);

    // create the directory path
    Path clusterDirectory = hoyaFileSystem.buildHoyaClusterDirPath(clustername);
    // delete the directory;
    boolean exists = hoyaFileSystem.getFileSystem().exists(clusterDirectory);
    if (exists) {
      log.info("Cluster found: destroying");
    } else {
      log.info("Cluster already destroyed");
    }
    hoyaFileSystem.getFileSystem().delete(clusterDirectory, true);

    List<ApplicationReport> instances = findAllLiveInstances(clustername);
    // detect any race leading to cluster creation during the check/destroy process
    // and report a problem.
    if (!instances.isEmpty()) {
      throw new HoyaException(EXIT_CLUSTER_IN_USE,
                              clustername + ": "
                              + E_DESTROY_CREATE_RACE_CONDITION
                              + " :" +
                              instances.get(0));
    }
    log.info("Destroyed cluster {}", clustername);
    return EXIT_SUCCESS;
  }

  /**
   * Force kill a yarn application by ID. No niceities here
   */
  public int actionEmergencyForceKill(String appId) throws YarnException,
                                                      IOException {
    verifyManagerSet();
    yarnClient.emergencyForceKill(appId);
    return EXIT_SUCCESS;
  }
  
  
  /**
   * AM to commit an asynchronous suicide
   */
  public int actionAmSuicide(String clustername,
                                 ActionAMSuicideArgs args) throws
                                                              YarnException,
                                                              IOException {
    HoyaClusterOperations clusterOperations =
      createClusterOperations(clustername);
    clusterOperations.amSuicide(args.message, args.exitcode, args.waittime);
    return EXIT_SUCCESS;
  }
  
  

  /**
   * Get the provider for this cluster
   * @param clusterSpec cluster spec
   * @return the provider instance
   * @throws HoyaException problems building the provider
   */
  private AbstractClientProvider createClientProvider(ClusterDescription clusterSpec)
    throws HoyaException {
    HoyaProviderFactory factory =
      HoyaProviderFactory.createHoyaProviderFactory(clusterSpec);
    return factory.createClientProvider();
  }

  /**
   * Get the provider for this cluster
   * @param provider the name of the provider
   * @return the provider instance
   * @throws HoyaException problems building the provider
   */
  private AbstractClientProvider createClientProvider(String provider)
    throws HoyaException {
    HoyaProviderFactory factory =
      HoyaProviderFactory.createHoyaProviderFactory(provider);
    return factory.createClientProvider();
  }

  /**
   * Create the cluster -saving the arguments to a specification file first
   * @param clustername cluster name
   * @return the status code
   * @throws YarnException Yarn problems
   * @throws IOException other problems
   * @throws BadCommandArgumentsException bad arguments.
   */
  public int actionCreate(String clustername, ActionCreateArgs createArgs) throws
                                               YarnException,
                                               IOException {

    actionBuild(clustername, createArgs);
    return startCluster(clustername, createArgs);
  }

  /**
   * Build up the cluster specification/directory
   *
   * @param clustername cluster name
   * @param buildInfo the arguments needed to build the cluster
   * @throws YarnException Yarn problems
   * @throws IOException other problems
   * @throws BadCommandArgumentsException bad arguments.
   */
  public int actionBuild(String clustername,
                           AbstractClusterBuildingActionArgs buildInfo) throws
                                               YarnException,
                                               IOException {


    int ret = actionBuildClassic(clustername, buildInfo);

    //now build the new style
    String newcluster = clustername + "-ac";
    Path newClusterDirectory =
      hoyaFileSystem.buildHoyaClusterDirPath(newcluster);
    hoyaFileSystem.getFileSystem().delete(newClusterDirectory, true);
    buildInstanceConfiguration(newcluster, buildInfo);
    return ret; 
  }

  /**
   * Build up the cluster specification/directory -classic logic
   *
   * @param clustername cluster name
   * @param buildInfo the arguments needed to build the cluster
   * @throws YarnException Yarn problems
   * @throws IOException other problems
   * @throws BadCommandArgumentsException bad arguments.
   */
  public int actionBuildClassic(String clustername,
                           AbstractClusterBuildingActionArgs buildInfo) throws
                                               YarnException,
                                               IOException {

    // verify that a live cluster isn't there
    HoyaUtils.validateClusterName(clustername);
    verifyManagerSet();
    verifyNoLiveClusters(clustername);

    // build up the paths in the DFS

    Path clusterDirectory = hoyaFileSystem.buildHoyaClusterDirPath(clustername);
    Path snapshotConfPath = new Path(clusterDirectory, HoyaKeys.SNAPSHOT_CONF_DIR_NAME);
    Path generatedConfPath =
      new Path(clusterDirectory, HoyaKeys.GENERATED_CONF_DIR_NAME);
    Path clusterSpecPath =
      new Path(clusterDirectory, HoyaKeys.CLUSTER_SPECIFICATION_FILE);

    //verify the directory is not there.
    hoyaFileSystem.verifyClusterDirectoryNonexistent(clustername, clusterDirectory);

    // actual creation takes place later, so that if the build files there
    // isn't a half build cluster in the filesystem
    
    Configuration conf = getConfig();
    // build up the initial cluster specification
    ClusterDescription clusterSpec = new ClusterDescription();

    Path appconfdir = buildInfo.getConfdir();
    requireArgumentSet(Arguments.ARG_CONFDIR, appconfdir);
    // Provider
    requireArgumentSet(Arguments.ARG_PROVIDER, buildInfo.getProvider());
    HoyaAMClientProvider hoyaAM = new HoyaAMClientProvider(conf);
    AbstractClientProvider provider;
    provider = createClientProvider(buildInfo.getProvider());

    // remember this
    clusterSpec.type = provider.getName();
    clusterSpec.name = clustername;
    clusterSpec.state = ClusterDescription.STATE_INCOMPLETE;
    long now = System.currentTimeMillis();
    clusterSpec.createTime = now;
    clusterSpec.setInfoTime(StatusKeys.INFO_CREATE_TIME_HUMAN,
                            StatusKeys.INFO_CREATE_TIME_MILLIS,
                            now);
    HoyaUtils.addBuildInfo(clusterSpec,"create");
    
    // build up the options map
    // first the defaults provided by the provider
    Map<String, String> options = new HashMap<String, String>();
    HoyaUtils.mergeEntries(options, hoyaAM.getDefaultClusterConfiguration());
    HoyaUtils.mergeEntries(options, provider.getDefaultClusterConfiguration());

    clusterSpec.options = options;
    
    
    //propagate the filename into the 1.x and 2.x value
    String fsDefaultName = conf.get(
      CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY);
    clusterSpec.setOptionifUnset(OptionKeys.SITE_XML_PREFIX +
                                 CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY,
                                 fsDefaultName);

    clusterSpec.setOptionifUnset(OptionKeys.SITE_XML_PREFIX +
                                 HoyaXmlConfKeys.FS_DEFAULT_NAME_CLASSIC,
                                 fsDefaultName);

    // Set temp dir as an option
    Path tmp = hoyaFileSystem.getTempPathForCluster(clustername);
    Path tempDirPath = new Path(tmp, "am");
    clusterSpec.setOption(OptionKeys.INTERNAL_AM_TMP_DIR,
                                 tempDirPath.toUri().toString());

    // patch in the properties related to the principals extracted from
    // the running hoya client

    propagatePrincipals(clusterSpec, conf);

    // next the options provided on the command line
    HoyaUtils.mergeMap(clusterSpec.options, buildInfo.getOptionsMap());

    // build the list of supported roles
    List<ProviderRole> supportedRoles = new ArrayList<ProviderRole>();
    // provider roles
    supportedRoles.addAll(provider.getRoles());
    // and any extra
    Map<String, String> argsRoleMap = buildInfo.getRoleMap();

    Map<String, Map<String, String>> clusterRoleMap =
      new HashMap<String, Map<String, String>>();

    // build the role map from default; set the instances
    for (ProviderRole role : supportedRoles) {
      String roleName = role.name;
      Map<String, String> clusterRole =
        provider.createDefaultClusterRole(roleName);
      // get the command line instance count
      String instanceCount = argsRoleMap.remove(roleName);
      // this is here in case we want to extract from the provider
      // the min #of instances
      int defInstances =
        HoyaUtils.getIntValue(clusterRole, RoleKeys.ROLE_INSTANCES, 0, 0, -1);
      instanceCount = Integer.toString(HoyaUtils.parseAndValidate(
          "count of role " + roleName, instanceCount, defInstances, 0, -1));
      clusterRole.put(RoleKeys.ROLE_INSTANCES, instanceCount);
      clusterRoleMap.put(roleName, clusterRole);
    }


    // any roles for counts which aren't in there, special
    // creation option
    for (Map.Entry<String, String> roleAndCount : argsRoleMap.entrySet()) {
      String name = roleAndCount.getKey();
      String count = roleAndCount.getValue();
      log.debug("Creating non-standard role {} of size {}", name, count);
      HashMap<String, String> newRole = new HashMap<String, String>();
      newRole.put(RoleKeys.ROLE_NAME, name);
      newRole.put(RoleKeys.ROLE_INSTANCES, count);
      clusterRoleMap.put(name, newRole);
    }

    //AM roles are special
    // add in the Hoya AM role(s)
    Collection<ProviderRole> amRoles = hoyaAM.getRoles();
    for (ProviderRole role : amRoles) {
      String roleName = role.name;
      Map<String, String> clusterRole =
        hoyaAM.createDefaultClusterRole(roleName);
      // get the command line instance count
      clusterRoleMap.put(roleName, clusterRole);
    }
    
    // finally, insert any roles that are implicitly defined
    // in the command line but for which we don't have any standard
    // templates
    Map<String, Map<String, String>> commandOptions =
      buildInfo.getRoleOptionMap();
    HoyaUtils.applyCommandLineRoleOptsToRoleMap(clusterRoleMap, commandOptions);

    clusterSpec.roles = clusterRoleMap;

    // App home or image
    if (buildInfo.getImage() != null) {
      if (!isUnset(buildInfo.getAppHomeDir())) {
        // both args have been set
        throw new BadCommandArgumentsException("Only one of "
                                               + Arguments.ARG_IMAGE
                                               + " and " +
                                               Arguments.ARG_APP_HOME +
                                               " can be provided");
      }
      clusterSpec.setImagePath(buildInfo.getImage().toUri().toString());
    } else {
      // the alternative is app home, which now MUST be set
      if (isUnset(buildInfo.getAppHomeDir())) {
        // both args have been set
        throw new BadCommandArgumentsException("Either " + Arguments.ARG_IMAGE
                                               + " or " +
                                               Arguments.ARG_APP_HOME +
                                               " must be provided");
      }
      clusterSpec.setApplicationHome(buildInfo.getAppHomeDir());
    }

    // set up the ZK binding
    String zookeeperRoot = buildInfo.getAppZKPath();
    if (isUnset(zookeeperRoot)) {
      zookeeperRoot =
        "/yarnapps_" + getAppName() + "_" + getUsername() + "_" + clustername;
    }
    clusterSpec.setZkPath(zookeeperRoot);
    clusterSpec.setZkPort(buildInfo.getZKport());
    clusterSpec.setZkHosts(buildInfo.getZKhosts());


    // another sanity check before the cluster dir is created: the config
    // dir
    FileSystem srcFS = FileSystem.get(appconfdir.toUri(), conf);
    if (!srcFS.isDirectory(appconfdir)) {
      throw new BadCommandArgumentsException(
        "Configuration directory specified in %s not valid: %s",
       Arguments.ARG_CONFDIR, appconfdir.toString());
    }
    clusterSpec.originConfigurationPath = snapshotConfPath.toUri().toASCIIString();
    clusterSpec.generatedConfigurationPath =
      generatedConfPath.toUri().toASCIIString();
    hoyaFileSystem.createClusterDirectories(clustername, getConfig());

    // save the specification to get a lock on this cluster name
    try {
      clusterSpec.save(hoyaFileSystem.getFileSystem(), clusterSpecPath, false);
    } catch (FileAlreadyExistsException fae) {
      throw new HoyaException(EXIT_CLUSTER_EXISTS,
                              PRINTF_E_INSTANCE_ALREADY_EXISTS, clustername,
                              clusterSpecPath);
    } catch (IOException e) {
      // this is probably a file exists exception too, but include it in the trace just in case
      throw new HoyaException(EXIT_CLUSTER_EXISTS, e,
                              PRINTF_E_INSTANCE_ALREADY_EXISTS, clustername,
                              clusterSpecPath);
    }

    // bulk copy
    FsPermission clusterPerms = getClusterDirectoryPermissions(conf);
    // first the original from wherever to the DFS
    HoyaUtils.copyDirectory(conf, appconfdir, snapshotConfPath, clusterPerms);

    // Data Directory
    Path datapath = new Path(clusterDirectory, HoyaKeys.DATA_DIR_NAME);

    log.debug("datapath={}", datapath);
    clusterSpec.dataPath = datapath.toUri().toString();

    // final specification review
    provider.reviewAndUpdateClusterSpec(clusterSpec);

    // here the configuration is set up. Mark it
    clusterSpec.state = ClusterDescription.STATE_CREATED;
    clusterSpec.save(hoyaFileSystem.getFileSystem(), clusterSpecPath, true);
    
    return EXIT_SUCCESS;
  }

  /**
   * Build up the AggregateConfiguration for an application instance then
   * persiste it
   * @param clustername name of the cluster
   * @param buildInfo the arguments needed to build the cluster
   * @return true if it worked
   * @throws YarnException
   * @throws IOException
   */
  
  public boolean buildInstanceConfiguration(String clustername,
                AbstractClusterBuildingActionArgs buildInfo) throws YarnException,
                                                             IOException {
    // verify that a live cluster isn't there
    HoyaUtils.validateClusterName(clustername);
    verifyManagerSet();
    verifyNoLiveClusters(clustername);
    Configuration conf = getConfig();


    Path appconfdir = buildInfo.getConfdir();
    requireArgumentSet(Arguments.ARG_CONFDIR, appconfdir);
    // Provider
    requireArgumentSet(Arguments.ARG_PROVIDER, buildInfo.getProvider());
    HoyaAMClientProvider hoyaAM = new HoyaAMClientProvider(conf);
    AbstractClientProvider provider =
      createClientProvider(buildInfo.getProvider());
    InstanceBuilder builder =
      new InstanceBuilder(hoyaFileSystem, getConfig(), clustername);
    

    ConfTree internal = new ConfTree();
    ConfTree resources = new ConfTree();
    ConfTree appConf = new ConfTree();
    ConfTree cmdLineConf = buildInfo.buildConfTree();

    AggregateConf instanceConf = new AggregateConf(internal, appConf, resources);
    
    hoyaAM.prepareInstanceConfiguration(instanceConf);
    provider.prepareInstanceConfiguration(instanceConf);
    
    ConfTreeOperations appConfOps = instanceConf.getAppConfOperations();
    ConfTreeOperations resOps = instanceConf.getResourceOperations();
    ConfTreeOperations internalOps = instanceConf.getInternalOperations();
    appConfOps.putAll(cmdLineConf);


    //all CLI role options
    Map<String, Map<String, String>> roleOptionMap =
      buildInfo.getRoleOptionMap();
    appConfOps.mergeComponents(roleOptionMap);

    //internal picks up hoya. values only
    internalOps.propagateGlobalKeys(appConf, "hoya.");
    //copy over role. and yarn. values ONLY to the resources
    resOps.propagateGlobalKeys(appConf, "role.");
    resOps.propagateGlobalKeys(appConf, "yarn.");
    resOps.mergeComponentsPrefix(roleOptionMap, "role.", true);
    resOps.mergeComponentsPrefix(roleOptionMap, "yarn.", true);


    builder.init(appconfdir, provider.getName(), instanceConf);
    builder.propagateFilename();
    builder.propagatePrincipals();
    builder.setImageDetails(buildInfo.getImage(), buildInfo.getAppHomeDir());

        
    String zookeeperRoot = buildInfo.getAppZKPath();
    if (isUnset(zookeeperRoot)) {
      zookeeperRoot =
        "/yarnapps_" + getAppName() + "_" + getUsername() + "_" + clustername;
    }
    builder.addZKPaths(buildInfo.getZKhosts(),
                       zookeeperRoot,
                       buildInfo.getZKport());

    
    try {
      builder.persist();
    } catch (LockAcquireFailedException e) {
      log.warn("Failed to get a Lock on {} : {}", builder, e);
      throw new BadClusterStateException("Failed to save " + clustername
                                         + ": " + e);
    }

    builder.takeSnapshotOfConfDir(appconfdir);

    return true;
  }
  
  public FsPermission getClusterDirectoryPermissions(Configuration conf) {
    String clusterDirPermsOct =
      conf.get(HOYA_CLUSTER_DIRECTORY_PERMISSIONS,
               DEFAULT_HOYA_CLUSTER_DIRECTORY_PERMISSIONS);
    return new FsPermission(clusterDirPermsOct);
  }

  /**
   * Verify that the Resource MAnager is configured, if not fail
   * with a useful error message
   * @throws BadCommandArgumentsException the exception raised on an invalid config
   */
  public void verifyManagerSet() throws BadCommandArgumentsException {
    InetSocketAddress rmAddr = HoyaUtils.getRmAddress(getConfig());
    if (!HoyaUtils.isAddressDefined(rmAddr)) {
      throw new BadCommandArgumentsException(
        "No valid Resource Manager address provided in the argument "
        + Arguments.ARG_MANAGER
        + " or the configuration property "
        + YarnConfiguration.RM_ADDRESS 
        + " value :" + rmAddr);
    }
  }

  /**
   * Create a cluster to the specification
   *
   * @param clusterSpec cluster specification
   * @param launchArgs
   * @return the exit code from the operation
   */
  public int executeClusterStart(Path clusterDirectory,
                                 ClusterDescription clusterSpec,
                                 LaunchArgsAccessor launchArgs)
      throws YarnException, IOException {

    // verify that a live cluster isn't there;
    String clustername = clusterSpec.name;
    deployedClusterName = clustername;
    HoyaUtils.validateClusterName(clustername);
    verifyNoLiveClusters(clustername);
    Configuration config = getConfig();
    boolean clusterSecure = HoyaUtils.isClusterSecure(config);
    
    //create the Hoya AM provider -this helps set up the AM
    HoyaAMClientProvider hoyaAM = new HoyaAMClientProvider(config);
    // cluster Provider
    AbstractClientProvider provider = createClientProvider(clusterSpec);
    // make sure the conf dir is valid;

    Path generatedConfDirPath =
      createPathThatMustExist(clusterSpec.generatedConfigurationPath);
    Path snapshotConfPath =
      createPathThatMustExist(clusterSpec.originConfigurationPath);

    // now build up the image path
    // TODO: consider supporting apps that don't have an image path
    Path imagePath;
    String csip = clusterSpec.getImagePath();
    if (!isUnset(csip)) {
      imagePath = createPathThatMustExist(csip);
    } else {
      imagePath = null;
      if (isUnset(clusterSpec.getApplicationHome())) {
        throw new BadClusterStateException(NO_IMAGE_OR_HOME_DIR_SPECIFIED);
      }
    }

    // final specification review
    hoyaAM.validateClusterSpec(clusterSpec);
    provider.validateClusterSpec(clusterSpec);

    // do a quick dump of the values first
    if (log.isDebugEnabled()) {
      log.debug(clusterSpec.toString());
    }

    YarnClientApplication application = yarnClient.createApplication();
    ApplicationSubmissionContext submissionContext =
      application.getApplicationSubmissionContext();
    ApplicationId appId = submissionContext.getApplicationId();
    // set the application name;
    submissionContext.setApplicationName(clustername);
    // app type used in service enum;
    submissionContext.setApplicationType(HoyaKeys.APP_TYPE);

    submissionContext.setMaxAppAttempts(config.getInt(KEY_HOYA_RESTART_LIMIT,
                                                      DEFAULT_HOYA_RESTART_LIMIT));

    if (HoyaServiceUtils.keepContainersAcrossSubmissions(submissionContext)) {
      log.info("Requesting cluster stays running over AM failure");
    }

    hoyaFileSystem.purgeHoyaAppInstanceTempFiles(clustername);
    Path tempPath = hoyaFileSystem.createHoyaAppInstanceTempPath(
            clustername,
            appId.toString()+"/am");
    String libdir = "lib";
    Path libPath = new Path(tempPath, libdir);
    hoyaFileSystem.getFileSystem().mkdirs(libPath);
    log.debug("FS={}, tempPath={}, libdir={}", hoyaFileSystem.getFileSystem(), tempPath, libPath);
    // Set up the container launch context for the application master
    ContainerLaunchContext amContainer =
      Records.newRecord(ContainerLaunchContext.class);

    // set local resources for the application master
    // local files or archives as needed
    // In this scenario, the jar file for the application master is part of the local resources
    Map<String, LocalResource> localResources =
      new HashMap<String, LocalResource>();
    // conf directory setup
    Path remoteHoyaConfPath = null;
    String relativeHoyaConfDir = null;
    String hoyaConfdirProp = System.getProperty(HoyaKeys.PROPERTY_HOYA_CONF_DIR);
    if (hoyaConfdirProp == null || hoyaConfdirProp.isEmpty()) {
      log.debug("No local configuration directory provided as system property");
    } else {
      File hoyaConfDir = new File(hoyaConfdirProp);
      if (!hoyaConfDir.exists()) {
        throw new BadConfigException(HOYA_CONFIGURATION_DIRECTORY_NOT_FOUND,
                                     hoyaConfDir);
      }
      Path localConfDirPath = HoyaUtils.createLocalPath(hoyaConfDir);
      log.debug("Copying Hoya AM configuration data from {}", localConfDirPath);
      remoteHoyaConfPath = new Path(clusterDirectory,
                                   HoyaKeys.SUBMITTED_HOYA_CONF_DIR);
      HoyaUtils.copyDirectory(config, localConfDirPath, remoteHoyaConfPath, null);
    }

    // the assumption here is that minimr cluster => this is a test run
    // and the classpath can look after itself

    if (!getUsingMiniMRCluster()) {

      log.debug("Destination is not a MiniYARNCluster -copying full classpath");

      // insert conf dir first
      if (remoteHoyaConfPath != null) {
        relativeHoyaConfDir = HoyaKeys.SUBMITTED_HOYA_CONF_DIR;
        Map<String, LocalResource> submittedConfDir =
          hoyaFileSystem.submitDirectory(remoteHoyaConfPath, relativeHoyaConfDir);
        HoyaUtils.mergeMaps(localResources, submittedConfDir);
      }

      log.debug("Copying JARs from local filesystem");
      // Copy the application master jar to the filesystem
      // Create a local resource to point to the destination jar path

      HoyaUtils.putJar(localResources,
                       hoyaFileSystem,
                       this.getClass(),
                       tempPath,
                       libdir,
                       HOYA_JAR);
    }

    // build up the configuration 
    // IMPORTANT: it is only after this call that site configurations
    // will be valid.

    propagatePrincipals(clusterSpec, config);

    Configuration clientConfExtras = new Configuration(false);

    // then build up the generated path.
    FsPermission clusterPerms = getClusterDirectoryPermissions(config);
    HoyaUtils.copyDirectory(config, snapshotConfPath, generatedConfDirPath,
                            clusterPerms);


    // add AM and provider specific artifacts to the resource map
    Map<String, LocalResource> providerResources;
    // standard AM resources
    providerResources = hoyaAM.prepareAMAndConfigForLaunch(hoyaFileSystem,
                                                         config,
                                                         clusterSpec,
                                                         snapshotConfPath,
                                                         generatedConfDirPath,
                                                         clientConfExtras,
                                                         libdir,
                                                         tempPath);
    localResources.putAll(providerResources);
    //add provider-specific resources
    providerResources = provider.prepareAMAndConfigForLaunch(hoyaFileSystem,
                                                         config,
                                                         clusterSpec,
                                                         snapshotConfPath,
                                                         generatedConfDirPath,
                                                         clientConfExtras,
                                                         libdir,
                                                         tempPath);

    localResources.putAll(providerResources);

    // now that the site config is fully generated, the provider gets
    // to do a quick review of them.
    log.debug("Preflight validation of cluster configuration");

    hoyaAM.preflightValidateClusterConfiguration(hoyaFileSystem, clustername, config,
                                                 clusterSpec,
                                                 clusterDirectory,
                                                 generatedConfDirPath,
                                                 clusterSecure
                                                );

    provider.preflightValidateClusterConfiguration(hoyaFileSystem, clustername, config,
                                                   clusterSpec,
                                                   clusterDirectory,
                                                   generatedConfDirPath,
                                                   clusterSecure
                                                  );


    // now add the image if it was set
    if (hoyaFileSystem.maybeAddImagePath(localResources, imagePath)) {
      log.debug("Registered image path {}", imagePath);
    }

    if (log.isDebugEnabled()) {
      for (Map.Entry<String, LocalResource> entry : localResources.entrySet()) {
        String key = entry.getKey();
        LocalResource val = entry.getValue();
        log.debug("{}={}", key, HoyaUtils.stringify(val.getResource()));
      }
    }
    
    // Set local resource info into app master container launch context
    amContainer.setLocalResources(localResources);


    // build the environment
    Map<String, String> env =
      HoyaUtils.buildEnvMap(clusterSpec.getOrAddRole(HoyaKeys.ROLE_HOYA_AM));
    String classpath = HoyaUtils.buildClasspath(relativeHoyaConfDir,
                                                libdir,
                                                getConfig(),
                                                getUsingMiniMRCluster());
    env.put("CLASSPATH", classpath);
    if (log.isDebugEnabled()) {
      log.debug("AM classpath={}", classpath);
      log.debug("Environment Map:\n{}", HoyaUtils.stringifyMap(env));
      log.debug("Files in lib path\n{}", hoyaFileSystem.listFSDir(libPath));
    }

    String rmAddr = launchArgs.getRmAddress();
    // spec out the RM address
    if (isUnset(rmAddr) && HoyaUtils.isRmSchedulerAddressDefined(config)) {
      rmAddr = NetUtils.getHostPortString(HoyaUtils.getRmSchedulerAddress(config));
    }

    // build up the args list, intially as anyting
    List<String> commands = new ArrayList<String>(20);
    commands.add(ApplicationConstants.Environment.JAVA_HOME.$() + "/bin/java");
    // insert any JVM options);
    hoyaAM.addJVMOptions(clusterSpec, commands);
    // enable asserts if the text option is set
    if (serviceArgs.isDebug()) {
      commands.add(HoyaKeys.JVM_ENABLE_ASSERTIONS);
      commands.add(HoyaKeys.JVM_ENABLE_SYSTEM_ASSERTIONS);
    }
    commands.add(String.format(HoyaKeys.FORMAT_D_CLUSTER_NAME, clustername));
    commands.add(String.format(HoyaKeys.FORMAT_D_CLUSTER_TYPE, provider.getName()));
    // add the hoya AM sevice entry point
    commands.add(HoyaAMArgs.CLASSNAME);

    // create action and the cluster name
    commands.add(HoyaActions.ACTION_CREATE);
    commands.add(clustername);

    // debug
    if (serviceArgs.isDebug()) {
      commands.add(Arguments.ARG_DEBUG);
    }
    
    // set the cluster directory path
    commands.add(Arguments.ARG_HOYA_CLUSTER_URI);
    commands.add(clusterDirectory.toUri().toString());

    if (!isUnset(rmAddr)) {
      commands.add(Arguments.ARG_RM_ADDR);
      commands.add(rmAddr);
    }

    if (serviceArgs.getFilesystemURL() != null) {
      commands.add(Arguments.ARG_FILESYSTEM);
      commands.add(serviceArgs.getFilesystemURL().toString());
    }

    if (clusterSecure) {
      // if the cluster is secure, make sure that
      // the relevant security settings go over
      propagateConfOption(commands,
                          config,
                          HoyaXmlConfKeys.KEY_HOYA_SECURITY_ENABLED);
      propagateConfOption(commands,
                          config,
                          DFSConfigKeys.DFS_NAMENODE_USER_NAME_KEY);
      Credentials credentials = new Credentials();
      String tokenRenewer = config.get(YarnConfiguration.RM_PRINCIPAL);
      if (isUnset(tokenRenewer)) {
        throw new BadConfigException(
          "Can't get Master Kerberos principal %s for the RM to use as renewer",
          YarnConfiguration.RM_PRINCIPAL
        );
      }

      // For now, only getting tokens for the default file-system.
      final Token<?>[] tokens = hoyaFileSystem.getFileSystem().addDelegationTokens(tokenRenewer, credentials);
      if (tokens != null) {
        for (Token<?> token : tokens) {
          log.debug("Got delegation token for {}; {}", hoyaFileSystem.getFileSystem().getUri(), token);
        }
      }
      DataOutputBuffer dob = new DataOutputBuffer();
      credentials.writeTokenStorageToStream(dob);
      ByteBuffer fsTokens = ByteBuffer.wrap(dob.getData(), 0, dob.getLength());
      amContainer.setTokens(fsTokens);
    } else {
      //insecure cluster: propagate user name via env variable
      String userName = UserGroupInformation.getCurrentUser().getUserName();
      log.debug(HADOOP_USER_NAME + "='{}'", userName);
      env.put(HADOOP_USER_NAME, userName);
    }
    // write out the path output
    commands.add("1>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/"+
                 STDOUT_HOYAAM);
    commands.add("2>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/"+
                 STDERR_HOYAAM);

    String cmdStr = HoyaUtils.join(commands, " ");
    log.info("Completed setting up app master command {}", cmdStr);

    amContainer.setCommands(commands);
    //fix the env variables
    amContainer.setEnvironment(env);
    // Set up resource type requirements
    Resource capability = Records.newRecord(Resource.class);
    // Amt. of memory resource to request for to run the App Master
    capability.setMemory(RoleKeys.DEFAULT_AM_MEMORY);
    capability.setVirtualCores(RoleKeys.DEFAULT_AM_V_CORES);
    // the Hoya AM gets to configure the AM requirements, not the custom provider
    hoyaAM.prepareAMResourceRequirements(clusterSpec, capability);
    submissionContext.setResource(capability);
    Map<String, ByteBuffer> serviceData = new HashMap<String, ByteBuffer>();
    // Service data is a binary blob that can be passed to the application
    // Not needed in this scenario
    provider.prepareAMServiceData(clusterSpec, serviceData);
    amContainer.setServiceData(serviceData);

    // The following are not required for launching an application master
    // amContainer.setContainerId(containerId);

    submissionContext.setAMContainerSpec(amContainer);

    // Set the priority for the application master
    
    int amPriority = config.getInt(KEY_HOYA_YARN_QUEUE_PRIORITY,
                                   DEFAULT_HOYA_YARN_QUEUE_PRIORITY);

    Priority pri = Records.newRecord(Priority.class);
    pri.setPriority(amPriority);
    submissionContext.setPriority(pri);

    // Set the queue to which this application is to be submitted in the RM
    // Queue for App master
    String amQueue = config.get(KEY_HOYA_YARN_QUEUE, DEFAULT_HOYA_YARN_QUEUE);

    submissionContext.setQueue(amQueue);

    // Submit the application to the applications manager
    // SubmitApplicationResponse submitResp = applicationsManager.submitApplication(appRequest);
    // Ignore the response as either a valid response object is returned on success
    // or an exception thrown to denote some form of a failure
    log.info("Submitting application to Resource Manager");

    // submit the application
    applicationId = yarnClient.submitApplication(submissionContext);

    int exitCode;
    // wait for the submit state to be reached
    ApplicationReport report = monitorAppToState(new Duration(ACCEPT_TIME),
      YarnApplicationState.ACCEPTED);


    // may have failed, so check that
    if (HoyaUtils.hasAppFinished(report)) {
      exitCode = buildExitCode(appId, report);
    } else {
      // exit unless there is a wait
      exitCode = EXIT_SUCCESS;

      int waittime = launchArgs.getWaittime();
      if (waittime != 0) {
        // waiting for state to change
        Duration duration = new Duration(waittime * 1000);
        duration.start();
        report = monitorAppToState(duration,
                                   YarnApplicationState.RUNNING);
        if (report != null &&
            report.getYarnApplicationState() == YarnApplicationState.RUNNING) {
          exitCode = EXIT_SUCCESS;
        } else {

          yarnClient.killRunningApplication(appId, "");
          exitCode = buildExitCode(appId, report);
        }
      }
    }
    return exitCode;
  }


  /**
   * Propagate any critical principals from the current site config down to the HBase one.
   * @param clusterSpec cluster spec
   * @param config config to read from
   */
  private void propagatePrincipals(ClusterDescription clusterSpec,
                                   Configuration config) {
    String dfsPrincipal = config.get(DFSConfigKeys.DFS_NAMENODE_USER_NAME_KEY);
    if (dfsPrincipal != null) {
      String siteDfsPrincipal = OptionKeys.SITE_XML_PREFIX +
                                DFSConfigKeys.DFS_NAMENODE_USER_NAME_KEY;
      clusterSpec.setOptionifUnset(siteDfsPrincipal, dfsPrincipal);
    }
  }


  private void propagateConfOption(List<String> command, Configuration conf,
                                   String key) {
    String val = conf.get(key);
    if (val != null) {
      command.add(Arguments.ARG_DEFINE);
      command.add(key + "=" + val);
    }
  }

  /**
   * Create a path that must exist in the cluster fs
   * @param uri uri to create
   * @return the path
   * @throws HoyaException if the path does not exist
   */
  public Path createPathThatMustExist(String uri) throws
                                                  HoyaException,
                                                  IOException {
    Path path = new Path(uri);
    verifyPathExists(path);
    return path;
  }

  public void verifyPathExists(Path path) throws HoyaException, IOException {
    if (!hoyaFileSystem.getFileSystem().exists(path)) {
      throw new BadClusterStateException(E_MISSING_PATH + path);
    }
  }

  /**
   * verify that a live cluster isn't there
   * @param clustername cluster name
   * @throws HoyaException with exit code EXIT_CLUSTER_LIVE
   * if a cluster of that name is either live or starting up.
   */
  public void verifyNoLiveClusters(String clustername) throws
                                                       IOException,
                                                       YarnException {
    List<ApplicationReport> existing = findAllLiveInstances(clustername);

    if (!existing.isEmpty()) {
      throw new HoyaException(EXIT_CLUSTER_IN_USE,
                              clustername + ": " + E_CLUSTER_RUNNING + " :" +
                              existing.get(0));
    }
  }

  public String getUsername() throws IOException {
    return UserGroupInformation.getCurrentUser().getShortUserName();
  }

  /**
   * Get the name of any deployed cluster
   * @return the cluster name
   */
  public String getDeployedClusterName() {
    return deployedClusterName;
  }

  @VisibleForTesting
  public void setDeployedClusterName(String deployedClusterName) {
    this.deployedClusterName = deployedClusterName;
  }

  /**
   * ask if the client is using a mini MR cluster
   * @return true if they are
   */
  private boolean getUsingMiniMRCluster() {
    return getConfig().getBoolean(YarnConfiguration.IS_MINI_YARN_CLUSTER,
                                  false);
  }

  /**
   * Get the application name used in the zookeeper root paths
   * @return an application-specific path in ZK
   */
  private String getAppName() {
    return "hoya";
  }

  /**
   * Wait for the app to start running (or go past that state)
   * @param duration time to wait
   * @return the app report; null if the duration turned out
   * @throws YarnException YARN or app issues
   * @throws IOException IO problems
   */
  @VisibleForTesting
  public ApplicationReport monitorAppToRunning(Duration duration)
    throws YarnException, IOException {
    return monitorAppToState(duration,
                             YarnApplicationState.RUNNING);
  }

  /**
   * Build an exit code for an application Id and its report.
   * If the report parameter is null, the app is killed
   * @param appId app
   * @param report report
   * @return the exit code
   */
  private int buildExitCode(ApplicationId appId,
                            ApplicationReport report) throws
                                                      IOException,
                                                      YarnException {
    if (null == report) {
      forceKillApplication("Reached client specified timeout for application");
      return EXIT_TIMED_OUT;
    }

    YarnApplicationState state = report.getYarnApplicationState();
    FinalApplicationStatus dsStatus = report.getFinalApplicationStatus();
    switch (state) {
      case FINISHED:
        if (FinalApplicationStatus.SUCCEEDED == dsStatus) {
          log.info("Application has completed successfully");
          return EXIT_SUCCESS;
        } else {
          log.info("Application finished unsuccessfully." +
                   "YarnState = {}, DSFinalStatus = {} Breaking monitoring loop",
                   state, dsStatus);
          return EXIT_YARN_SERVICE_FINISHED_WITH_ERROR;
        }

      case KILLED:
        log.info("Application did not finish. YarnState={}, DSFinalStatus={}",
                 state, dsStatus);
        return EXIT_YARN_SERVICE_KILLED;

      case FAILED:
        log.info("Application Failed. YarnState={}, DSFinalStatus={}", state,
                 dsStatus);
        return EXIT_YARN_SERVICE_FAILED;
      default:
        //not in any of these states
        return EXIT_SUCCESS;
    }
  }

  /**
   * Monitor the submitted application for reaching the requested state.
   * Will also report if the app reaches a later state (failed, killed, etc)
   * Kill application if duration!= null & time expires. 
   * @param duration how long to wait -must be more than 0
   * @param desiredState desired state.
   * @return the application report -null on a timeout
   * @throws YarnException
   * @throws IOException
   */
  @VisibleForTesting
  public ApplicationReport monitorAppToState(
    Duration duration, YarnApplicationState desiredState)
    throws YarnException, IOException {
    return monitorAppToState(applicationId, desiredState, duration);
  }

  /**
   * Get the report of a this application
   * @return the app report or null if it could not be found.
   * @throws IOException
   * @throws YarnException
   */
  public ApplicationReport getApplicationReport() throws
                                                  IOException,
                                                  YarnException {
    return yarnClient.getApplicationReport(applicationId);
  }

  /**
   * Monitor the submitted application for reaching the requested state.
   * Will also report if the app reaches a later state (failed, killed, etc)
   * Kill application if duration!= null & time expires. 
   * @param appId Application Id of application to be monitored
   * @param duration how long to wait -must be more than 0
   * @param desiredState desired state.
   * @return the application report -null on a timeout
   * @throws YarnException
   * @throws IOException
   */
  @VisibleForTesting
  public ApplicationReport monitorAppToState(
    ApplicationId appId, YarnApplicationState desiredState, Duration duration)
    throws YarnException, IOException {
    return yarnClient.monitorAppToState(appId, desiredState, duration);
  }

  /**
   * Kill the submitted application by sending a call to the ASM
   * @throws YarnException
   * @throws IOException
   */
  public boolean forceKillApplication(String reason)
    throws YarnException, IOException {
    if (applicationId != null) {
      yarnClient.killRunningApplication(applicationId, reason);
      return true;
    }
    return false;
  }

  /**
   * List Hoya instances belonging to a specific user
   * @param user user: "" means all users
   * @return a possibly empty list of Hoya AMs
   */
  @VisibleForTesting
  public List<ApplicationReport> listHoyaInstances(String user)
    throws YarnException, IOException {
    return serviceRegistryClient.listInstances();
  }

  /**
   * Implement the list action: list all nodes
   * @return exit code of 0 if a list was created
   */
  @VisibleForTesting
  public int actionList(String clustername) throws IOException, YarnException {
    verifyManagerSet();

    String user = UserGroupInformation.getCurrentUser().getUserName();
    List<ApplicationReport> instances = listHoyaInstances(user);

    if (clustername == null || clustername.isEmpty()) {
      log.info("Hoya instances for {}: {}",
               (user != null ? user : "all users"),
               instances.size());
      for (ApplicationReport report : instances) {
        logAppReport(report);
      }
      return EXIT_SUCCESS;
    } else {
      HoyaUtils.validateClusterName(clustername);
      log.debug("Listing cluster named {}", clustername);
      ApplicationReport report =
        findClusterInInstanceList(instances, clustername);
      if (report != null) {
        logAppReport(report);
        return EXIT_SUCCESS;
      } else {
        throw unknownClusterException(clustername);
      }
    }
  }

  /**
   * Log the application report at INFO
   * @param report report to log
   */
  public void logAppReport(ApplicationReport report) {
    log.info(HoyaUtils.appReportToString(report, "\n"));
  }

  /**
   * Implement the islive action: probe for a cluster of the given name existing
   * @return exit code
   */
  @VisibleForTesting
  public int actionFlex(String name, ActionFlexArgs args) throws YarnException, IOException {
    verifyManagerSet();
    log.debug("actionFlex({})", name);
    Map<String, Integer> roleInstances = new HashMap<String, Integer>();
    Map<String, String> roleMap = args.getRoleMap();
    for (Map.Entry<String, String> roleEntry : roleMap.entrySet()) {
      String key = roleEntry.getKey();
      String val = roleEntry.getValue();
      try {
        roleInstances.put(key, Integer.valueOf(val));
      } catch (NumberFormatException e) {
        throw new BadCommandArgumentsException("Requested count of role %s" +
                                               " is not a number: \"%s\"",
                                               key, val);
      }
    }
    if (!args.isPersist()) {
      log.warn(
        "Persist flag ignored: the updated specification is always persisted");
    }
    return flex(name, roleInstances, true);
  }

  /**
   * Test for a cluster existing probe for a cluster of the given name existing
   * in the filesystem. If the live param is set, it must be a live cluster
   * @return exit code
   */
  @VisibleForTesting
  public int actionExists(String name, boolean live) throws YarnException, IOException {
    verifyManagerSet();
    log.debug("actionExists({}, {})", name, live);

    //initial probe for a cluster in the filesystem
    Path clusterDirectory = hoyaFileSystem.buildHoyaClusterDirPath(name);
    if (!hoyaFileSystem.getFileSystem().exists(clusterDirectory)) {
      throw unknownClusterException(name);
    }
    
    //test for liveness if desired

    if (live) {
      ApplicationReport instance = findInstance(name);
      if (instance == null) {
        log.info("cluster {} not running", name);
        return EXIT_FALSE;
      } else {
        // the app exists, but it may be in a terminated state
        HoyaUtils.OnDemandReportStringifier report =
          new HoyaUtils.OnDemandReportStringifier(instance);
        YarnApplicationState state =
          instance.getYarnApplicationState();
        if (state.ordinal() >= YarnApplicationState.FINISHED.ordinal()) {
          //cluster in the list of apps but not running
          log.info("Cluster {} found but is in state {}", name, state);
          log.debug("State {}", report);
          return EXIT_FALSE;
        }
        log.info("Cluster {} is running:\n{}", name, report);
      }
    } else {
      log.info("Cluster {} exists but is not running", name);

    }
    return EXIT_SUCCESS;
  }


  /**
   * Kill a specific container of the cluster
   * @param name cluster name
   * @param args arguments
   * @return exit code
   * @throws YarnException
   * @throws IOException
   */
  public int actionKillContainer(String name,
                                 ActionKillContainerArgs args) throws
                                                               YarnException,
                                                               IOException {
    String id = args.id;
    if (HoyaUtils.isUnset(id)) {
      throw new BadCommandArgumentsException("Missing container id");
    }
    log.info("killingContainer {}:{}", name, id);
    HoyaClusterOperations clusterOps =
      new HoyaClusterOperations(bondToCluster(name));
    try {
      clusterOps.killContainer(id);
    } catch (NoSuchNodeException e) {
      throw new BadClusterStateException("Container %s not found in cluster %s",
                                         id, name);
    }
    return EXIT_SUCCESS;
  }

  /**
   * Echo operation (not currently wired up to command line)
   * @param name cluster name
   * @param args arguments
   * @return the echoed text
   * @throws YarnException
   * @throws IOException
   */
  public String actionEcho(String name, ActionEchoArgs args) throws
                                                             YarnException,
                                                             IOException {
    String message = args.message;
    if (message == null) {
      throw new BadCommandArgumentsException("missing message");
    }
    HoyaClusterOperations clusterOps =
      new HoyaClusterOperations(bondToCluster(name));
    return clusterOps.echo(message);
  }

  /**
   * Get at the service registry operations
   * @return registry client -valid after the service is inited.
   */
  public ServiceRegistryClient getServiceRegistryClient() {
    return serviceRegistryClient;
  }

  /**
   * Find an instance of a hoya application belong to the current user
   * @param appname application name
   * @return the app report or null if none is found
   * @throws YarnException YARN issues
   * @throws IOException IO problems
   */
  private ApplicationReport findInstance(String appname) throws
                                                        YarnException,
                                                        IOException {
    return serviceRegistryClient.findInstance(appname);
  }

  /**
   * find all live instances of a specific app -if there is >1 in the cluster,
   * this returns them all. State should be running or less
   * @param appname application name
   * @return the list of all matching application instances
   */
  private List<ApplicationReport> findAllLiveInstances(String appname)
    throws YarnException, IOException {
    
    return serviceRegistryClient.findAllLiveInstances(appname);
  }


  public ApplicationReport findClusterInInstanceList(List<ApplicationReport> instances,
                                                     String appname) {
    return yarnClient.findClusterInInstanceList(instances, appname);
  }

  /**
   * Connect to a Hoya AM
   * @param app application report providing the details on the application
   * @return an instance
   * @throws YarnException
   * @throws IOException
   */
  private HoyaClusterProtocol connect(ApplicationReport app) throws
                                                              YarnException,
                                                              IOException {

    try {
      return RpcBinder.getProxy(getConfig(),
                                yarnClient.getRmClient(),
                                app,
                                CONNECT_TIMEOUT,
                                RPC_TIMEOUT);
    } catch (InterruptedException e) {
      throw new HoyaException(HoyaExitCodes.EXIT_TIMED_OUT,
                              e,
                              "Interrupted waiting for communications with the HoyaAM");
    }
  }


  /**
   * Status operation
   *
   * @param clustername cluster name
   * @param outfile filename : if not null indicates output is to be saved
   * to this file
   * @return 0 -for success, else an exception is thrown
   * @throws YarnException
   * @throws IOException
   */
  @VisibleForTesting
  public int actionStatus(String clustername, String outfile) throws
                                              YarnException,
                                              IOException {
    verifyManagerSet();
    HoyaUtils.validateClusterName(clustername);
    ClusterDescription status = getClusterDescription(clustername);
    String text = status.toJsonString();
    if (outfile == null) {
      log.info(text);
    } else {
      status.save(new File(outfile).getAbsoluteFile());
    }
    return EXIT_SUCCESS;
  }

  /**
   * Version Details
   * @return exit code
   */
  public int actionVersion() {
    HoyaVersionInfo.loadAndPrintVersionInfo(log);
    return EXIT_SUCCESS;
  }

  /**
   * Freeze the cluster
   *
   * @param clustername cluster name
   * @param freezeArgs arguments to the freeze
   * @return EXIT_SUCCESS if the cluster was not running by the end of the operation
   */
  public int actionFreeze(String clustername,
                          ActionFreezeArgs freezeArgs) throws
                                                            YarnException,
                                                            IOException {
    verifyManagerSet();
    HoyaUtils.validateClusterName(clustername);
    int waittime = freezeArgs.getWaittime();
    String text = freezeArgs.message;
    boolean forcekill = freezeArgs.force;
    log.debug("actionFreeze({}, reason={}, wait={}, force={})", clustername,
              text,
              waittime,
              forcekill);
    
    //is this actually a known cluster? 
    locateClusterSpecification(clustername);
    ApplicationReport app = findInstance(clustername);
    if (app == null) {
      // exit early
      log.info("Cluster {} not running", clustername);
      // not an error to freeze a frozen cluster
      return EXIT_SUCCESS;
    }
    log.debug("App to freeze was found: {}:\n{}", clustername,
              new HoyaUtils.OnDemandReportStringifier(app));
    if (app.getYarnApplicationState().ordinal() >=
        YarnApplicationState.FINISHED.ordinal()) {
      log.info("Cluster {} is a terminated state {}", clustername,
               app.getYarnApplicationState());
      return EXIT_SUCCESS;
    }
    ApplicationId appId = app.getApplicationId();

    if (forcekill) {
      //escalating to forced kill
      yarnClient.killRunningApplication(appId,
                                        "Forced freeze of " + clustername +
                                       ": " + text);
    } else {
      try {
        HoyaClusterProtocol appMaster = connect(app);
        Messages.StopClusterRequestProto r =
          Messages.StopClusterRequestProto
                  .newBuilder()
                  .setMessage(text)
                  .build();
        appMaster.stopCluster(r);

        log.debug("Cluster stop command issued");

      } catch (YarnException e) {
        log.warn("Exception while trying to terminate {}: {}", clustername, e);
        return EXIT_FALSE;
      } catch (IOException e) {
        log.warn("Exception while trying to terminate {}: {}", clustername, e);
        return EXIT_FALSE;
      }
    }

    //wait for completion. We don't currently return an exception during this process
    //as the stop operation has been issued, this is just YARN.
    try {
      if (waittime > 0) {
        ApplicationReport applicationReport =
          monitorAppToState(appId,
                            YarnApplicationState.FINISHED,
                            new Duration(waittime * 1000));
        if (applicationReport == null) {
          log.info("application did not shut down in time");
          return EXIT_FALSE;
        }
      }
    } catch (YarnException e) {
      log.warn("Exception while waiting for the cluster {} to shut down: {}",
               clustername, e);
    } catch (IOException e) {
      log.warn("Exception while waiting for the cluster {} to shut down: {}",
               clustername, e);
    }

    return EXIT_SUCCESS;
  }

  /*
   * Creates a site conf with entries from clientProperties of ClusterStatus
   * @param desc ClusterDescription, can be null
   * @param clustername, can be null
   * @return site conf
   */
  public Configuration getSiteConf(ClusterDescription desc, String clustername)
      throws YarnException, IOException {
    if (desc == null) {
      desc = getClusterDescription();
    }
    if (clustername == null) {
      clustername = getDeployedClusterName();
    }
    String description = "Hoya cluster " + clustername;
    
    Configuration siteConf = new Configuration(false);
    for (String key : desc.clientProperties.keySet()) {
      siteConf.set(key, desc.clientProperties.get(key), description);
    }
    return siteConf;
  }


  /**
   * get the cluster configuration
   * @param clustername cluster name
   * @return the cluster name
   */

  @SuppressWarnings(
    {"UseOfSystemOutOrSystemErr", "IOResourceOpenedButNotSafelyClosed"})
  public int actionGetConf(String clustername, ActionGetConfArgs confArgs) throws
                                               YarnException,
                                               IOException {
    int exitCode;
    File outfile = null;
    
    if (confArgs.getOutput() != null) {
      outfile = new File(confArgs.getOutput());
    }

    String format = confArgs.getFormat();
    verifyManagerSet();
    HoyaUtils.validateClusterName(clustername);
    ClusterDescription status = getClusterDescription(clustername);
    Writer writer;
    boolean toPrint;
    if (outfile != null) {
      writer = new FileWriter(outfile);
      toPrint = false;
    } else {
      writer = new StringWriter();
      toPrint = true;
    }
    try {
      String description = "Hoya cluster " + clustername;
      if (format.equals(Arguments.FORMAT_XML)) {
        Configuration siteConf = getSiteConf(status, clustername);
        siteConf.writeXml(writer);
      } else if (format.equals(Arguments.FORMAT_PROPERTIES)) {
        Properties props = new Properties();
        props.putAll(status.clientProperties);
        props.store(writer, description);
      } else {
        throw new BadCommandArgumentsException("Unknown format: " + format);
      }
    } finally {
      // data is written.
      // close the file
      writer.close();
    }
    // then, if this is not a file write, print it
    if (toPrint) {
      // not logged
      System.err.println(writer.toString());
    }
    return EXIT_SUCCESS;
  }

  /**
   * Restore a cluster
   */
  public int actionThaw(String clustername, ActionThawArgs thaw) throws YarnException, IOException {
    HoyaUtils.validateClusterName(clustername);
    // see if it is actually running and bail out;
    verifyManagerSet();
    verifyNoLiveClusters(clustername);


    //start the cluster
    return startCluster(clustername, thaw);
  }

  /**
   * Load and start a cluster specification.
   * This assumes that all validation of args and cluster state
   * have already taken place
   *
   * @param clustername name of the cluster.
   * @param launchArgs
   * @return the exit code
   * @throws YarnException
   * @throws IOException
   */
  private int startCluster(String clustername, LaunchArgsAccessor launchArgs) throws
                                               YarnException,
                                               IOException {
    Path clusterSpecPath = locateClusterSpecification(clustername);

    ClusterDescription clusterSpec = hoyaFileSystem.loadAndValidateClusterSpec(clusterSpecPath);
    Path clusterDirectory = hoyaFileSystem.buildHoyaClusterDirPath(clustername);

    return executeClusterStart(clusterDirectory, clusterSpec, launchArgs);
  }

  /**
   * get the path of a cluster
   * @param clustername name of the cluster
   * @return the path to the cluster specification
   * @throws HoyaException if the specification is not there
   */
  public Path locateClusterSpecification(String clustername) throws
                                                             YarnException,
                                                             IOException {
    return hoyaFileSystem.locateClusterSpecification(clustername);
  }

  /**
   * Implement flexing
   * @param clustername name of the cluster
   * @param roleInstances map of new role instances
   * @param persist (ignored) flag of persistence policy
   * @return EXIT_SUCCESS if the #of nodes in a live cluster changed
   * @throws YarnException
   * @throws IOException
   */
  public int flex(String clustername,
                  Map<String, Integer> roleInstances,
                  boolean persist) throws
                                   YarnException,
                                   IOException {
    verifyManagerSet();
    HoyaUtils.validateClusterName(clustername);
    Path clusterSpecPath = locateClusterSpecification(clustername);
    ClusterDescription clusterSpec =
      hoyaFileSystem.loadAndValidateClusterSpec(clusterSpecPath);

    HoyaUtils.addBuildInfo(clusterSpec, "flex");
    clusterSpec.setInfoTime(StatusKeys.INFO_FLEX_TIME_HUMAN,
                            StatusKeys.INFO_FLEX_TIME_MILLIS,
                            System.currentTimeMillis());
    for (Map.Entry<String, Integer> entry : roleInstances.entrySet()) {
      String role = entry.getKey();
      int count = entry.getValue();
      if (count < 0) {
        throw new BadCommandArgumentsException("Requested number of " + role
            + " instances is out of range");
      }

      clusterSpec.setDesiredInstanceCount(role, count);

      log.debug("Flexed cluster specification ( {} -> {}) : \n{}",
                role,
                count,
                clusterSpec);
    }
    Path clusterDirectory = hoyaFileSystem.buildHoyaClusterDirPath(clustername);
    log.debug("Saving the cluster specification to {}", clusterSpecPath);
    // save the specification
    if (!hoyaFileSystem.updateClusterSpecification(
      clusterDirectory,
      clusterSpecPath,
      clusterSpec)) {
      log.warn("Failed to save new cluster size to {}", clusterSpecPath);
    }
    int exitCode = EXIT_FALSE;

    // now see if it is actually running and bail out if not
    verifyManagerSet();
    ApplicationReport instance = findInstance(clustername);
    if (instance != null) {
      log.info("Flexing running cluster");
      HoyaClusterProtocol appMaster = connect(instance);
      HoyaClusterOperations clusterOps = new HoyaClusterOperations(appMaster);
      if (clusterOps.flex(clusterSpec)) {
        log.info("Cluster size updated");
        exitCode = EXIT_SUCCESS;
      } else {
        log.info("Requested cluster size is the same as current size: no change");
      }
    } else {
      log.info("No running cluster to update");
    }
    return exitCode;
  }


  /**
   * Load the persistent cluster description
   * @param clustername name of the cluster
   * @return the description in the filesystem
   * @throws IOException any problems loading -including a missing file
   */
  @VisibleForTesting
  public ClusterDescription loadPersistedClusterDescription(String clustername) throws
                                                                                IOException {
    Path clusterDirectory = hoyaFileSystem.buildHoyaClusterDirPath(clustername);
    Path clusterSpecPath =
      new Path(clusterDirectory, HoyaKeys.CLUSTER_SPECIFICATION_FILE);
    return ClusterDescription.load(hoyaFileSystem.getFileSystem(), clusterSpecPath);
  }

  /**
   * Load the persistent cluster description
   * @return the description in the filesystem
   * @throws IOException any problems loading -including a missing file
   */
  @VisibleForTesting
  public ClusterDescription loadPersistedClusterDescription() throws IOException {
    return loadPersistedClusterDescription(deployedClusterName);
  }

    /**
     * Connect to a live cluster and get its current state
     * @param clustername the cluster name
     * @return its description
     */
  @VisibleForTesting
  public ClusterDescription getClusterDescription(String clustername) throws
                                                                 YarnException,
                                                                 IOException {
    HoyaClusterOperations clusterOperations =
      createClusterOperations(clustername);
    return clusterOperations.getClusterDescription();
  }

  /**
   * Connect to the cluster and get its current state
   * @return its description
   */
  @VisibleForTesting
  public ClusterDescription getClusterDescription() throws
                                               YarnException,
                                               IOException {
    return getClusterDescription(getDeployedClusterName());
  }

  /**
   * List all node UUIDs in a role
   * @param role role name or "" for all
   * @return an array of UUID strings
   * @throws IOException
   * @throws YarnException
   */
  @VisibleForTesting
  public String[] listNodeUUIDsByRole(String role) throws
                                               IOException,
                                               YarnException {
    return createClusterOperations()
              .listNodeUUIDsByRole(role);
  }

  /**
   * List all nodes in a role. This is a double round trip: once to list
   * the nodes in a role, another to get their details
   * @param role
   * @return an array of ContainerNode instances
   * @throws IOException
   * @throws YarnException
   */
  @VisibleForTesting
  public List<ClusterNode> listClusterNodesInRole(String role) throws
                                               IOException,
                                               YarnException {
    return createClusterOperations().listClusterNodesInRole(role);
  }

  /**
   * Get the details on a list of uuids
   * @param uuids
   * @return a possibly empty list of node details
   * @throws IOException
   * @throws YarnException
   */
  @VisibleForTesting
  public List<ClusterNode> listClusterNodes(String[] uuids) throws
                                               IOException,
                                               YarnException {

    if (uuids.length == 0) {
      // short cut on an empty list
      return new LinkedList<ClusterNode>();
    }
    return createClusterOperations().listClusterNodes(uuids);
  }

  /**
   * Get a node from the AM
   * @param uuid uuid of node
   * @return deserialized node
   * @throws IOException IO problems
   * @throws NoSuchNodeException if the node isn't found
   */
  @VisibleForTesting
  public ClusterNode getNode(String uuid) throws IOException, YarnException {
    return createClusterOperations().getNode(uuid);
  }

  /**
   * Bond to a running cluster
   * @param clustername cluster name
   * @return the AM RPC client
   * @throws HoyaException if the cluster is unkown
   */
  private HoyaClusterProtocol bondToCluster(String clustername) throws
                                                                  YarnException,
                                                                  IOException {
    verifyManagerSet();
    if (clustername == null) {
      throw unknownClusterException("");
    }
    ApplicationReport instance = findInstance(clustername);
    if (null == instance) {
      throw unknownClusterException(clustername);
    }
    return connect(instance);
  }

  /**
   * Create a cluster operations instance against a given cluster
   * @param clustername cluster name
   * @return a bonded cluster operations instance
   * @throws YarnException YARN issues
   * @throws IOException IO problems
   */
  private HoyaClusterOperations createClusterOperations(String clustername) throws
                                                                            YarnException,
                                                                            IOException {
    HoyaClusterProtocol hoyaAM = bondToCluster(clustername);
    return new HoyaClusterOperations(hoyaAM);
  }

  /**
   * Create a cluster operations instance against the active cluster
   * -returning any previous created one if held.
   * @return a bonded cluster operations instance
   * @throws YarnException YARN issues
   * @throws IOException IO problems
   */
  public HoyaClusterOperations createClusterOperations() throws
                                                         YarnException,
                                                         IOException {
    if (hoyaClusterOperations == null) {
      hoyaClusterOperations =
        createClusterOperations(getDeployedClusterName());
    }
    return hoyaClusterOperations;
  }

  /**
   * Wait for an instance of a named role to be live (or past it in the lifecycle)
   * @param role role to look for
   * @param timeout time to wait
   * @return the state. If still in CREATED, the cluster didn't come up
   * in the time period. If LIVE, all is well. If >LIVE, it has shut for a reason
   * @throws IOException IO
   * @throws HoyaException Hoya
   * @throws WaitTimeoutException if the wait timed out
   */
  @VisibleForTesting
  public int waitForRoleInstanceLive(String role, long timeout)
    throws WaitTimeoutException, IOException, YarnException {
    return createClusterOperations().waitForRoleInstanceLive(role, timeout);
  }

  /**
   * Generate an exception for an unknown cluster
   * @param clustername cluster name
   * @return an exception with text and a relevant exit code
   */
  public UnknownClusterException unknownClusterException(String clustername) {
    return new UnknownClusterException(E_UNKNOWN_CLUSTER 
                             +": \""+ clustername+ "\"");
  }

  @Override
  public String toString() {
    return "HoyaClient in state " + getServiceState()
           + " and cluster name " + deployedClusterName;
  }

  /**
   * Get all YARN applications
   * @return a possibly empty list
   * @throws YarnException
   * @throws IOException
   */
  @VisibleForTesting
  public List<ApplicationReport> getApplications() throws YarnException, IOException {
    return yarnClient.getApplications();
  }

  @VisibleForTesting
  public ApplicationReport getApplicationReport(ApplicationId appId)
    throws YarnException, IOException {
    return yarnClient.getApplicationReport(appId);
  }
}
