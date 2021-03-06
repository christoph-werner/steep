package agent

import AddressConstants.LOCAL_AGENT_ADDRESS_PREFIX
import AddressConstants.PROCESSCHAIN_PROGRESS_CHANGED
import ConfigConstants
import com.google.common.cache.CacheBuilder
import db.PluginRegistryFactory
import helper.FileSystemUtils.readRecursive
import helper.OutputCollector
import helper.UniqueID
import helper.withRetry
import io.prometheus.client.Gauge
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.eventbus.unregisterAwait
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import model.metadata.Service
import model.plugins.call
import model.processchain.Argument
import model.processchain.ArgumentVariable
import model.processchain.Executable
import model.processchain.ProcessChain
import runtime.DockerRuntime
import runtime.OtherRuntime
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.round
import kotlin.reflect.full.callSuspend

/**
 * An agent that executes process chains locally
 * @param vertx the Vert.x instance
 * @dispatcher a coroutine dispatcher used to execute blocking process chains
 * in an asynchronous manner. Should be a [java.util.concurrent.ThreadPoolExecutor]
 * converted to a [CoroutineDispatcher] through [kotlinx.coroutines.asCoroutineDispatcher].
 * @author Michel Kraemer
 */
class LocalAgent(private val vertx: Vertx, val dispatcher: CoroutineDispatcher,
    private val config: JsonObject = vertx.orCreateContext.config()) : Agent, CoroutineScope {
  companion object {
    /**
     * A cache that tracks which directories we already created
     */
    private val mkdirCache = CacheBuilder.newBuilder()
        .expireAfterAccess(1, TimeUnit.MINUTES)
        .maximumSize(1000)
        .build<String, Boolean>()

    /**
     * The total number of times an executable with a given serviceId had
     * to be retried
     */
    private val gaugeRetries = Gauge.build()
        .name("steep_local_agent_retries")
        .labelNames("serviceId")
        .help("The total number of times an executable with a given " +
            "serviceId had to be retried")
        .register()
  }

  override val id: String = UniqueID.next()

  override val coroutineContext: CoroutineContext by lazy {
    // use a SupervisorJob because executables may fail and we want to retry
    // them without cancelling the parent coroutine context
    dispatcher + SupervisorJob()
  }

  private val pluginRegistry = PluginRegistryFactory.create()
  private val outputLinesToCollect = config
      .getInteger(ConfigConstants.AGENT_OUTPUT_LINES_TO_COLLECT, 100)

  private val otherRuntime by lazy { OtherRuntime() }
  private val dockerRuntime by lazy { DockerRuntime(config) }

  override suspend fun execute(processChain: ProcessChain): Map<String, List<Any>> {
    val outputs = processChain.executables
        .flatMap { it.arguments }
        .filter { it.type == Argument.Type.OUTPUT }
    val mkdirs = mkdirsForOutputs(outputs)
    var progress: Double? = null

    // temporarily register a consumer that can cancel the process chain
    val address = LOCAL_AGENT_ADDRESS_PREFIX + processChain.id
    val consumer = vertx.eventBus().consumer<JsonObject>(address).handler { msg ->
      when (msg.body().getString("action")) {
        "cancel" -> cancel()
        "getProgress" -> msg.reply(progress)
        else -> msg.fail(400, "Invalid action")
      }
    }

    // keep track of current progress and notify listeners
    fun setProgress(newProgress: Double) {
      val roundedNew = round(newProgress * 100) / 100.0
      val oldProgress = progress
      if (oldProgress == null) {
        progress = roundedNew
      } else if (roundedNew >= 0) {
        progress = roundedNew
      }
      if (progress != oldProgress) {
        vertx.eventBus().send(PROCESSCHAIN_PROGRESS_CHANGED, json {
          obj(
              "processChainId" to processChain.id,
              "estimatedProgress" to progress
          )
        })
      }
    }

    // execute the process chain
    val executor = Executors.newSingleThreadExecutor()
    try {
      // create all required output directories
      for (exec in mkdirs) {
        execute(exec, executor)
      }

      // run executables and track progress
      for ((index, exec) in processChain.executables.withIndex()) {
        withRetry(exec.retries) { attempt ->
          if (attempt > 1) {
            gaugeRetries.labels(exec.serviceId ?: "<unknown>").inc()
          }
          execute(exec, executor) { p ->
            val step = 1.0 / processChain.executables.size
            setProgress(step * index + step * p)
          }
        }

        if ((index + 1) < processChain.executables.size || progress != null) {
          setProgress((index + 1).toDouble() / processChain.executables.size)
        }
      }
    } finally {
      // make sure the consumer is unregistered
      consumer.unregisterAwait()

      // close executor
      executor.shutdown()
    }

    // create list of results
    val fs = vertx.fileSystem()
    return outputs.associate {
      val outputAdapter = pluginRegistry.findOutputAdapter(it.dataType)
      it.variable.id to (outputAdapter?.call(it, processChain, vertx) ?:
          readRecursive(it.variable.value, fs))
    }
  }

  /**
   * Try to cancel the process chain execution. Interrupt the thread that
   * executes the process chain.
   */
  fun cancel() {
    coroutineContext.cancel()
  }

  private suspend fun execute(exec: Executable, executor: ExecutorService,
      progressUpdater: ((Double) -> Unit)? = null) {
    val collector = if (progressUpdater != null) {
      ProgressReportingOutputCollector(outputLinesToCollect, exec, progressUpdater)
    } else {
      OutputCollector(outputLinesToCollect)
    }

    if (exec.runtime == Service.RUNTIME_DOCKER) {
      interruptableAsync(executor) {
        dockerRuntime.execute(exec, collector)
      }.await()
    } else if (exec.runtime == Service.RUNTIME_OTHER) {
      interruptableAsync(executor) {
        otherRuntime.execute(exec, collector)
      }.await()
    } else {
      val r = pluginRegistry.findRuntime(exec.runtime) ?:
          throw IllegalStateException("Unknown runtime: `${exec.runtime}'")
      if (r.compiledFunction.isSuspend) {
        r.compiledFunction.callSuspend(exec, outputLinesToCollect, vertx)
      } else {
        interruptableAsync(executor) {
          r.compiledFunction.call(exec, outputLinesToCollect, vertx)
        }.await()
      }
    }
  }

  /**
   * Executes the given [block] with the given [executor] in the
   * [coroutineContext]. Handles cancellation requests and interrupts the
   * thread that executes the [block].
   */
  private fun <R> interruptableAsync(executor: ExecutorService, block: () -> R): Deferred<R> = async {
    suspendCancellableCoroutine { cont ->
      val f = executor.submit {
        try {
          cont.resume(block())
        } catch (ie: InterruptedException) {
          cont.resumeWithException(CancellationException(ie.message ?:
              "Process chain execution was interrupted"))
        } catch (t: Throwable) {
          cont.resumeWithException(t)
        }
      }

      cont.invokeOnCancellation {
        f.cancel(true)
      }
    }
  }

  /**
   * Create `mkdir` commands for all output directories
   * @param outputs the outputs
   * @return the `mkdir` commands
   */
  private fun mkdirsForOutputs(outputs: List<Argument>): List<Executable> {
    val so = outputs.map {
      if (it.dataType == Argument.DATA_TYPE_DIRECTORY) {
        it.variable.value
      } else {
        File(it.variable.value).parent
      }
    }.filter { path ->
      if (mkdirCache.getIfPresent(path) == null) {
        mkdirCache.put(path, true)
        true
      } else {
        false
      }
    }

    return so.chunked(100).map { w ->
      Executable(
        path = "mkdir",
        arguments = listOf(
            Argument(
                label = "-p",
                variable = ArgumentVariable(UniqueID.next(), "true"),
                type = Argument.Type.INPUT,
                dataType = Argument.DATA_TYPE_BOOLEAN
            )
        ) + w.map { o ->
          Argument(
              variable = ArgumentVariable(UniqueID.next(), o),
              type = Argument.Type.INPUT
          )
        }
      )
    }
  }

  private inner class ProgressReportingOutputCollector(maxLines: Int,
      private val exec: Executable, private val progressUpdater: (Double) -> Unit) :
      OutputCollector(maxLines) {
    private val progressEstimator = exec.serviceId?.let {
      pluginRegistry.findProgressEstimator(it) }

    override fun collect(line: String) {
      super.collect(line)
      progressEstimator?.let { pe ->
        // make copy of lines because we are about to launch an asynchronous
        // task and need to avoid concurrent modification
        val linesCopy = ArrayList(lines())

        GlobalScope.launch(coroutineContext) {
          val progress = pe.call(exec, linesCopy, vertx)
          progress?.let { progressUpdater(progress) }
        }
      }
    }
  }
}
