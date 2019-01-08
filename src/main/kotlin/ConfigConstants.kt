/**
 * Configuration constants
 * @author Michel Kraemer
 */
object ConfigConstants {
  /**
   * The path to the service metadata JSON file
   */
  const val SERVICE_METADATA_FILE = "jobmanager.serviceMetadataFile"

  /**
   * Path where temporary files should be stored
   */
  const val TMP_PATH = "jobmanager.tmpPath"

  /**
   * The IP address (or hostname) the JobManager uses to announce itself within
   * in the cluster
   */
  const val CLUSTER_ANNOUNCE_ADDRESS = "jobmanager.cluster.announceAddress"

  /**
   * A list of IP addresses (or hostnames) of cluster members
   */
  const val CLUSTER_MEMBERS = "jobmanager.cluster.members"

  /**
   * `true` the the JobManager should deploy an HTTP server
   */
  const val HTTP_ENABLED = "jobmanager.http.enabled"

  /**
   * The host to bind the JobManager's HTTP server to
   */
  const val HTTP_HOST = "jobmanager.http.host"

  /**
   * The port the JobManager's HTTP server should listen on
   */
  const val HTTP_PORT = "jobmanager.http.port"

  /**
   * The maximum size of HTTP POST bodies in bytes
   */
  const val HTTP_POST_MAX_SIZE = "jobmanager.http.postMaxSize"

  /**
   * The interval in which the controller looks for accepted submissions
   */
  const val CONTROLLER_LOOKUP_INTERVAL = "jobmanager.controller.lookupIntervalMilliseconds"

  /**
   * The interval in which the scheduler looks for registered process chains
   */
  const val SCHEDULER_LOOKUP_INTERVAL = "jobmanager.scheduler.lookupIntervalMilliseconds"

  /**
   * `true` if this JobManager instance should be able to execute process
   * chains through [agent.LocalAgent]
   */
  const val AGENT_ENABLED = "jobmanager.agent.enabled"

  /**
   * The database driver (see [db.SubmissionRegistryFactory] for valid values)
   */
  const val DB_DRIVER = "jobmanager.db.driver"

  /**
   * The database URL
   */
  const val DB_URL = "jobmanager.db.url"

  /**
   * The database username
   */
  const val DB_USERNAME = "jobmanager.db.username"

  /**
   * The database password
   */
  const val DB_PASSWORD = "jobmanager.db.password"

  /**
   * Get all configuration keys from this class
   * @return the list of configuration keys
   */
  fun getConfigKeys(): List<String> = ConfigConstants::class.java.fields
      .map { it.get(null) }
      .filterIsInstance<String>()
}
