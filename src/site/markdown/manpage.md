<!---
~~ Licensed under the Apache License, Version 2.0 (the "License");
~~ you may not use this file except in compliance with the License.
~~ You may obtain a copy of the License at
~~
~~   http://www.apache.org/licenses/LICENSE-2.0
~~
~~ Unless required by applicable law or agreed to in writing, software
~~ distributed under the License is distributed on an "AS IS" BASIS,
~~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
~~ See the License for the specific language governing permissions and
~~ limitations under the License. See accompanying LICENSE file.
-->

# slider: HBase on YARN

## NAME

slider - HBase on YARN

## SYNOPSIS

Slider enables HBase and Accumulo clusters do be dynamically created on a YARN-managed datacenter.
The program can be used to create, pause, and shutdown Slider clusters. It can also be used to list current clusters.
 
## CONCEPTS

1. A *Slider cluster* represents a short-lived or long-lived set of HBase servers; one HBase Master and one or more HBase Region Servers

1. A *cluster* is built by deploying an *image* across multiple nodes in a YARN cluster.

1. An *image* is a *tar.gz* file containing a supported version of HBase.

1. Images are kept in the HDFS filesystem and identified by their path names; filesystem permissions can be used to share images amongst users.

1. All clusters are private to the user account that created them; images may be shared

1. An *image configuration* is a directory that is overlaid file-by-file onto the conf/ directory inside the HBase image.

1. Users can have multiple image configurations -they too are kept in HDFS, identified by their path names, and can be shared by setting the appropriate permissions, along with a configuration template file.

1. Only those files provided in the image configuration directory overwrite the default values contained in the image; all other configuration files are retained.

1. Late-binding properties can also be provided to a cluster at create time.

1. Slider will overwrite some of the HBase configuration properties to configure the dynamically created HBase cluster nodes to bind correctly to each other.

1. A *cluster state directory* is a directory created in HDFS describing the cluster; it records user-specified properties including the image and image configuration paths, overridden properties, and creation-time node requirements.

1. The cluster state directory also contains dynamically created information as to the location of HBase region servers -this is used to place the region servers close to their previous locations -ideally on the server used before, falling back to the same rack and then elsewhere in the same cluster.

1. A user can create a cluster using a named image.

1. A cluster can be frozen, saving its final state to its cluster state directory. All the HBase processes are shut down.

1. A frozen cluster can be thawed -a new set of HBase processes are started on or near the servers where the earlier processes were previously running.

1. A frozen cluster can be destroyed. simply by deleting the cluster state directory.

1. A frozen cluster can be reimaged. This can update the cluster's HBase version and its configuration. When the cluster is started, the changes are picked up.

1. Running clusters can be listed. 

1. A cluster consists of a set of role instances; 

1. The supported roles depends upon the provider behind Slider: HBase only has `worker` and `master`

1. the number of instances of each role must be specified when a cluster is created.

1. The number of instances of each role can be varied dynamically.

1. Users can flex a cluster: adding or removing instances of specific roles dynamically.
If the cluster is running, the changes will have immediate effect. If the cluster
is stopped, the flexed cluster size will be picked up when the cluster is next
started.


<!--- ======================================================================= -->

## Invoking Slider

 
    slider <ACTION> [<CLUSTER>] [<OPTIONS>]


<!--- ======================================================================= -->

## COMMON COMMAND-LINE OPTIONS

### `--conf configuration.xml`

Configure the Slider client. This allows the filesystem, zookeeper instance and other properties to be picked up from the configuration file, rather than on the command line.

Important: *this configuration file is not propagated to the HBase cluster configuration*. It is purely for configuring the client itself. 

### `-D name=value`

Define a Hadoop configuration option which overrides any options in the configuration XML files of the image or in the image configuration directory. The values will be persisted. Configuration options are only passed to the cluster when creating or reconfiguring a cluster.



### `-m, --manager url`

URL of the YARN resource manager


### `--fs filesystem-uri`

Use the specific filesystem URI as an argument to the operation.





<!--- ======================================================================= -->


<!--- ======================================================================= -->

## Actions

CLUSTER COMMANDS



### `build cluster`

Build a cluster specification of the given name, with the specific options.

The cluster is not started; this can be done later with a `thaw` command.

### `create cluster`

Build and run a cluster of the given name, using the specified image. If a configuration directory is specified, it's configuration files override those in the image. 

The `--wait` parameter, if provided, specifies the time to wait until the YARN application is actually running. Even after the YARN application has started, there may be some delay for the HBase cluster to start up.

### Arguments for `build` and `create` 


##### `--option <name> <value>`  

Set a cluster option. These are interpreted by specific cluster providers.

Example:

Set an option to be passed into the `-site.xml` file of the target system, reducing
the HDFS replication factor to 2. (

    --option site.dfs.blocksize 128m
    
Increase the number of YARN containers which must fail before the Slider cluster
itself fails.
    
    -O hoya.container.failure.threshold 16

##### `--appconf dfspath`

A URI path to the configuration directory containing the template cluster specification. The path must be on a filesystem visible to all nodes in the YARN cluster.

1. Only one configuration directory can be specified.
1. The contents of the directory will only be read when the cluster is created/built.

Example:

    --appconf hdfs://namenode/users/slider/conf/hbase-template
    --appconf file://users/accumulo/conf/template



##### `--apphome localpath`

A path to the home dir of a pre-installed application. If set when a Slider
cluster is created, the cluster will run with the binaries pre-installed
on the nodes at this location

*Important*: this is a path in the local filesystem which must be present
on all hosts in the cluster

Example

    --apphome /usr/hadoop/hbase

##### `--image path`

The full path in Hadoop HDFS  to a `.tar` or `.tar.gz` file containing 
the binaries needed to run the target application -HBase or Accumulo as appropriate.

Example

    --image hdfs://namenode/shared/binaries/hbase-0.96.tar.gz

##### `--role <rolename> <count>`

The desired number of instances of a role.


Example

    --role worker 16

#### `--roleopt <rolename> <option> <value>`

Set any role-specific option, such as its YARN memory requirements.

Example

    --roleopt master yarn.memory 2048
    --roleopt worker yarn.memory max


##### `--zkport port` 

The port on which the zookeeper processes are listening.

    Example
    
        --zkport 29181

##### `--zkhosts host1[,host2,host3, ...] `

The list of hosts on which the ZK quorum is running.


Example

    --zkhosts zk1,zk2,zk3,zk4,zk5,zk6,zk7,zk8,zk8,zk10,zk11


### `destroy \<cluster>`

Destroy a (stopped) cluster.

Important: This deletes all the database data as well as the cluster information

Example

    slider destroy cluster1

### `exists \<cluster> [--live]`

Probe the existence of the named Slider cluster. If the `--live` flag is set, the cluster
must be running

If not, an error code is returned.

When the --live` flag is unset, the command looks for the cluster to be
defined in the filesystem -its operation state is not checked.

Return codes

     0 : cluster is defined in the filesystem
    70 : cluster is unknown

Example:

    slider exists cluster4

### Live Tests

When the --live` flag is set, the cluster must be running for the command
to succeed

1. The probe does not check the status of any Slider-deployed services, merely that a cluster has been deployed
1. A cluster that is finished or failed is not considered to be live.

Return codes

     0 : cluster is running
    -1 : cluster exists but is not running
    70 : cluster is unknown


Example:

    slider exists cluster4 --live

### `flex <cluster> [--role rolename count]* `

Flex the number of workers in a cluster to the new value. If greater than before -nodes will be added. If less, nodes will be removed  from the cluster. 

This operation has a return value of 0 if the size of a running cluster was changed. 

It returns -1 if there is no running cluster, or the size of the flexed cluster matches that of the original -in which case the cluster state does not change.

Example

    slider flex cluster1 --role worker 8 --filesystem hdfs://host:port
    slider flex cluster1 --role master 2 --filesystem hdfs://host:port
    

### `freeze <cluster>  [--force] [--wait time] [--message text]`

freeze the cluster. The HBase cluster is scheduled to be destroyed. The cluster settings are retained in HDFS.

The `--wait` argument can specify a time in seconds to wait for the cluster to be frozen.

The `--force` flag causes the SliderAM to be bypassed, and YARN asked directly
to terminate the application. This will freeze a cluster that has hung or
is otherwise not responding.

The `--message` argument supplies an optional text message to be used in
the request: this will appear in the application's diagnostics in the YARN RM UI.

If an unknown (or already frozen) cluster is named, no error is returned.

Examples

    slider freeze cluster1 --wait 30
    slider freeze cluster2 --force --message "maintenance session"


### `getconf <cluster>  [--out file] [--format xml|properties]`

Get the configuration properties needed for hbase clients to connect to the cluster. Hadoop XML format files (the default) and Java properties files can be generated.
The output can be streamed to the console in `stdout`, or it can be saved to a file via the `--out` parameter

### `killcontainer <cluster> --id container-id`

Kill a container in the cluster. This is useful primarily for testing the cluster's
resilience to failures.

Container IDs can be determined from the cluster status JSON document.

### `list <cluster>`

List running Slider clusters visible to the user.

If a cluster name is given and there is no running cluster with that name, an error is returned. 

Example

    slider list
    slider list cluster1

### `status <cluster> [--out <filename>]`

Get the status of the named Slider cluster in JSON format. A filename can be used to 
specify the destination.

Examples:

    slider status cluster1 --manager host:port
    
    slider status cluster2 --manager host:port --out status.json



### `thaw <cluster> [--wait time`]

Resume a frozen cluster: recreate the cluster from its previous state. This will include a best-effort attempt to create the same number of nodes as before, though their locations may be different.
The same zookeeper bindings as before will be used.

Examples:

    slider thaw cluster2
    slider thaw cluster1 --wait 60


If a cluster is already running, this is a no-op

### `version`

The command `slider version` prints out information about the compiled
Slider application, the version of Hadoop against which it was built -and
the version of Hadoop that is currently on its classpath.

Note that this is the client-side Hadoop version, not that running on the server, though
that can be obtained in the cluster status operation


### `emergency-force-kill <applicationID>`

This attempts to force kill any YARN application referenced by application ID.
There is no attempt to notify the running AM. 

If the application ID is `all` then all slider instances belonging to the current
user are killed.

These are clearly abnormal operations; they are here primarily for testing
-and documented for completeness.


Example

    slider emergency-force-kill application_1386596138212_0001

### `am-suicide <cluster> [--exitcode code] [--message message] [--wait time]`

This operation is purely for testing Slider Application Master restart;
it triggers an asynchronous self-destruct operation in the AM -an 
operation that does not make any attempt to cleanly shut down the process. 

If the application has not exceeded its restart limit (as set by
`hoya.yarn.restart.limit`), YARN will attempt to restart the failed application.

Example

    slider am-suicide --exitcode 1 --wait 5000 -message "test"

<!--- ======================================================================= -->


## Cluster Naming

Cluster names must:

1. be at least one character long
1. begin with a lower case letter
1. All other characters must be in the range \[a-z,0-9,-, -]
1. All upper case characters are converted to lower case
 
Example valid names:

    slider1
    hbase-cluster
    hbase_cluster
    accumulo_m1_tserve4


<!--- ======================================================================= -->
## Cluster Options

Cluster options are intended to apply across a cluster, are set with the 
`--option` or `-O` arguments, and are saved in the `options {}` clause
in the JSON specification.

### HBase options

`hbase.master.command`: The single command to execute on the HBase master,
in the command sequence: `hbase master start`

for example, if the parameter was
  
    -O hbase.master.command version

Slider would would run the HBase master with the command

    hbase version start

This would not actually create the master -as stated, it is for testing purposes.

### General

`hoya.test`

This notifies the application that this is a test run, and that the application should behave in a way to aid testing. Currently all this does is

1. Limit the number of attempts to start the AM to one.
1. Enable verbose output in the client-AM RPC


<!--- ======================================================================= -->

## Role Options

Here are some role options that are intended to be common across roles, though
it is up to the provider and role whether or not an option is used.

1. *Important:* Unknown options are ignored. If an option does not appear to work,
check the spelling.
1. All values are strings; if an integer is required, it should be quoted

### generic

* `role.name` name of the role
* `role.instances` number of instances desired 

* `app.infoport`: For applications that support a web port that can be externally configured,
the web port to use. A value of "0" means that an arbitrary port should be picked.

### YARN parameters

YARN parameters are interpreted by Slider itself -so will always be read, validated
and acted on.

* `yarn.app.retries`: number of times to attempt to retry application execution.
 
* `yarn.memory`: how much memory (in GB) to request 

* `yarn.vcores`: number of cores to request; how this is translated into physical
core allocation is a YARN-specific (possibly scheduler-specific) feature.

### JVM parameters

These should be interpreted by all providers that start a JVM in the specific role

* `jvm.heapsize`: JVM heap size for Java applications in MB.
* `jvm.opts`: JVM options other than heap size



