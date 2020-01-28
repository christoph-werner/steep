package db

import com.fasterxml.jackson.module.kotlin.readValue
import com.mongodb.ConnectionString
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexModel
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.InsertOneModel
import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import com.mongodb.reactivestreams.client.gridfs.GridFSBucket
import com.mongodb.reactivestreams.client.gridfs.GridFSBuckets
import db.SubmissionRegistry.ProcessChainStatus
import helper.DefaultSubscriber
import helper.JsonUtils
import helper.bulkWriteAwait
import helper.closeAwait
import helper.countDocumentsAwait
import helper.deleteAwait
import helper.findAwait
import helper.findOneAndUpdateAwait
import helper.findOneAwait
import helper.insertOneAwait
import helper.readAwait
import helper.updateManyAwait
import helper.updateOneAwait
import helper.writeAwait
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import model.Submission
import model.processchain.ProcessChain
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.time.Instant
import java.time.format.DateTimeFormatter.ISO_INSTANT
import io.vertx.ext.mongo.impl.JsonObjectBsonAdapter as wrap

/**
 * A submission registry that keeps objects in a MongoDB database
 * @param vertx the current Vert.x instance
 * @param connectionString the MongoDB connection string (e.g.
 * `mongodb://localhost:27017/database`)
 * @param createIndexes `true` if indexes should be created
 * @author Michel Kraemer
 */
class MongoDBSubmissionRegistry(private val vertx: Vertx,
    connectionString: String, createIndexes: Boolean = true) : SubmissionRegistry {
  companion object {
    private val log = LoggerFactory.getLogger(MongoDBSubmissionRegistry::class.java)

    /**
     * Collection and property names
     */
    private const val COLL_SEQUENCE = "sequence"
    private const val COLL_SUBMISSIONS = "submissions"
    private const val COLL_PROCESS_CHAINS = "processChains"
    private const val BUCKET_PROCESS_CHAINS = "processChains"
    private const val BUCKET_PROCESS_CHAIN_RESULTS = "processChainResults"
    private const val BUCKET_EXECUTION_STATES = "executionStates"
    private const val BUCKET_SUBMISSION_RESULTS = "submissionResults"
    private const val INTERNAL_ID = "_id"
    private const val ID = "id"
    private const val SUBMISSION_ID = "submissionId"
    private const val START_TIME = "startTime"
    private const val END_TIME = "endTime"
    private const val STATUS = "status"
    private const val ERROR_MESSAGE = "errorMessage"
    private const val SEQUENCE = "sequence"
    private const val VALUE = "value"

    /**
     * Fields to exclude when querying the `submissions` collection
     */
    private val SUBMISSION_EXCLUDES = json {
      obj(
          ERROR_MESSAGE to 0,
          SEQUENCE to 0
      )
    }

    /**
     * Fields to exclude when querying the `processChains` collection
     */
    private val PROCESS_CHAIN_EXCLUDES = json {
      obj(
          SUBMISSION_ID to 0,
          STATUS to 0,
          START_TIME to 0,
          END_TIME to 0,
          ERROR_MESSAGE to 0,
          SEQUENCE to 0
      )
    }
    private val PROCESS_CHAIN_EXCLUDES_BUT_SUBMISSION_ID =
        PROCESS_CHAIN_EXCLUDES.copy().also { it.remove(SUBMISSION_ID) }
  }

  private val client: MongoClient
  private val db: MongoDatabase
  private val collSequence: MongoCollection<JsonObject>
  private val collSubmissions: MongoCollection<JsonObject>
  private val collProcessChains: MongoCollection<JsonObject>
  private val bucketProcessChains: GridFSBucket
  private val bucketProcessChainResults: GridFSBucket
  private val bucketExecutionStates: GridFSBucket
  private val bucketSubmissionResults: GridFSBucket

  init {
    val cs = ConnectionString(connectionString)
    client = SharedMongoClient.create(cs)
    db = client.getDatabase(cs.database)

    collSequence = db.getCollection(COLL_SEQUENCE, JsonObject::class.java)
    collSubmissions = db.getCollection(COLL_SUBMISSIONS, JsonObject::class.java)
    collProcessChains = db.getCollection(COLL_PROCESS_CHAINS, JsonObject::class.java)

    bucketProcessChains = GridFSBuckets.create(db, BUCKET_PROCESS_CHAINS)
    bucketProcessChainResults = GridFSBuckets.create(db, BUCKET_PROCESS_CHAIN_RESULTS)
    bucketExecutionStates = GridFSBuckets.create(db, BUCKET_EXECUTION_STATES)
    bucketSubmissionResults = GridFSBuckets.create(db, BUCKET_SUBMISSION_RESULTS)

    if (createIndexes) {
      // create indexes for `submission` collection
      collSubmissions.createIndexes(listOf(
          IndexModel(Indexes.ascending(STATUS), IndexOptions().background(true)),
          IndexModel(Indexes.ascending(SEQUENCE), IndexOptions().background(true))
      )).subscribe(object : DefaultSubscriber<String>() {
        override fun onError(t: Throwable) {
          log.error("Could not create index on collection `$COLL_SUBMISSIONS'", t)
        }
      })

      // create indexes for `processChains` collection
      collProcessChains.createIndexes(listOf(
          IndexModel(Indexes.ascending(SUBMISSION_ID), IndexOptions().background(true)),
          IndexModel(Indexes.ascending(STATUS), IndexOptions().background(true)),
          IndexModel(Indexes.ascending(SEQUENCE), IndexOptions().background(true))
      )).subscribe(object : DefaultSubscriber<String>() {
        override fun onError(t: Throwable) {
          log.error("Could not create index on collection `$COLL_PROCESS_CHAINS'", t)
        }
      })
    }
  }

  override suspend fun close() {
    client.close()
  }

  /**
   * Get a next [n] sequential numbers for a given [collection]
   */
  private suspend fun getNextSequence(collection: String, n: Int = 1): Long {
    val doc = collSequence.findOneAndUpdateAwait(json {
      obj(
          INTERNAL_ID to collection
      )
    }, json {
      obj(
          "\$inc" to obj(
              VALUE to n.toLong()
          )
      )
    }, FindOneAndUpdateOptions().upsert(true))
    return doc?.getLong(VALUE, 0L) ?: 0L
  }

  override suspend fun addSubmission(submission: Submission) {
    val sequence = getNextSequence(COLL_SUBMISSIONS)
    val doc = JsonUtils.toJson(submission)
    doc.put(INTERNAL_ID, submission.id)
    doc.remove(ID)
    doc.put(SEQUENCE, sequence)
    collSubmissions.insertOneAwait(doc)
  }

  /**
   * Deserialize a submission from a database [document]
   */
  private fun deserializeSubmission(document: JsonObject): Submission {
    document.remove(ERROR_MESSAGE)
    document.remove(SEQUENCE)
    document.put(ID, document.getString(INTERNAL_ID))
    document.remove(INTERNAL_ID)
    return JsonUtils.fromJson(document)
  }

  override suspend fun findSubmissions(size: Int, offset: Int, order: Int):
      Collection<Submission> {
    val docs = collSubmissions.findAwait(JsonObject(), size, offset, json {
      obj(
          SEQUENCE to order
      )
    }, SUBMISSION_EXCLUDES)
    return docs.map { deserializeSubmission(it) }
  }

  override suspend fun findSubmissionById(submissionId: String): Submission? {
    val doc = collSubmissions.findOneAwait(json {
      obj(
          INTERNAL_ID to submissionId
      )
    }, SUBMISSION_EXCLUDES)
    return doc?.let { deserializeSubmission(it) }
  }

  override suspend fun findSubmissionIdsByStatus(status: Submission.Status) =
      collSubmissions.findAwait(json {
        obj(
            STATUS to status.toString()
        )
      }, projection = json {
        obj(
            INTERNAL_ID to 1
        )
      }).map { it.getString(INTERNAL_ID) }

  override suspend fun countSubmissions() =
      collSubmissions.countDocumentsAwait(JsonObject())

  /**
   * Set a [field] of a document with a given [id] in the given [collection] to
   * a specified [value]
   */
  private suspend fun updateField(collection: MongoCollection<JsonObject>,
      id: String, field: String, value: Any?) {
    collection.updateOneAwait(json {
      obj(
          INTERNAL_ID to id
      )
    }, json {
      obj(
          if (value != null) {
            "\$set" to obj(
                field to value
            )
          } else {
            "\$unset" to obj(
                field to ""
            )
          }
      )
    })
  }

  /**
   * Set a [field] of a document with a given [id] and an [expectedValue]
   * in the given [collection] to a specified [newValue]
   */
  private suspend fun updateField(collection: MongoCollection<JsonObject>,
      id: String, field: String, expectedValue: Any?, newValue: Any?) {
    collection.updateOneAwait(json {
      obj(
          INTERNAL_ID to id,
          field to expectedValue
      )
    }, json {
      obj(
          if (newValue != null) {
            "\$set" to obj(
                field to newValue
            )
          } else {
            "\$unset" to obj(
                field to ""
            )
          }
      )
    })
  }

  /**
   * Get the value of a [field] of a document with the given [id] and [type]
   * from the given [collection]
   */
  private suspend inline fun <reified T> getField(collection: MongoCollection<JsonObject>,
      type: String, id: String, field: String): T {
    val doc = collection.findOneAwait(json {
      obj(
          INTERNAL_ID to id
      )
    }, json {
      obj(
          field to 1
      )
    })

    @Suppress
    if (doc == null) {
      throw NoSuchElementException("There is no $type with ID `$id'")
    }

    return doc.getValue(field) as T
  }

  private suspend inline fun <reified T> getSubmissionField(id: String, field: String): T =
      getField(collSubmissions, "submission", id, field)

  private suspend inline fun <reified T> getProcessChainField(id: String, field: String): T =
      getField(collProcessChains, "process chain", id, field)

  override suspend fun fetchNextSubmission(currentStatus: Submission.Status,
      newStatus: Submission.Status): Submission? {
    val doc: JsonObject? = collSubmissions.findOneAndUpdateAwait(json {
      obj(
          STATUS to currentStatus.toString()
      )
    }, json {
      obj(
          "\$set" to obj(
              STATUS to newStatus.toString()
          )
      )
    }, FindOneAndUpdateOptions().projection(wrap(SUBMISSION_EXCLUDES)))
    return doc?.let { deserializeSubmission(it) }
  }

  override suspend fun setSubmissionStartTime(submissionId: String, startTime: Instant) {
    updateField(collSubmissions, submissionId, START_TIME, startTime)
  }

  override suspend fun setSubmissionEndTime(submissionId: String, endTime: Instant) {
    updateField(collSubmissions, submissionId, END_TIME, endTime)
  }

  override suspend fun setSubmissionStatus(submissionId: String,
      status: Submission.Status) {
    updateField(collSubmissions, submissionId, STATUS, status.toString())
  }

  override suspend fun getSubmissionStatus(submissionId: String) =
      getSubmissionField<String>(submissionId, STATUS).let {
        Submission.Status.valueOf(it)
      }

  private suspend fun writeGridFSDocument(bucket: GridFSBucket, id: String,
      obj: JsonObject?) {
    if (obj == null) {
      bucket.findAwait(json {
        obj(
            "filename" to id
        )
      })?.let {
        bucket.deleteAwait(it.id)
      }
    } else {
      val str = obj.encode()
      val stream = bucket.openUploadStream(id)
      stream.writeAwait(ByteBuffer.wrap(str.toByteArray()))
      stream.closeAwait()
    }
  }

  private suspend fun readGridFSDocument(bucket: GridFSBucket, id: String): ByteBuffer? {
    val file = bucket.findAwait(json {
      obj(
          "filename" to id
      )
    })

    return file?.let {
      val stream = bucket.openDownloadStream(it.id)
      val buf = ByteBuffer.allocate(it.length.toInt())
      stream.readAwait(buf)
      stream.closeAwait()
      buf
    }
  }

  override suspend fun setSubmissionResults(submissionId: String, results: Map<String, List<Any>>?) {
    writeGridFSDocument(bucketSubmissionResults, submissionId,
        results?.let{ JsonObject(it) })
  }

  override suspend fun getSubmissionResults(submissionId: String): Map<String, List<Any>>? {
    val submissionCount = collSubmissions.countDocumentsAwait(json {
      obj(
          INTERNAL_ID to submissionId
      )
    })
    if (submissionCount == 0L) {
      throw NoSuchElementException("There is no submission with ID `$submissionId'")
    }

    val buf = readGridFSDocument(bucketSubmissionResults, submissionId)
    return buf?.let { JsonUtils.mapper.readValue(it.array()) }
  }

  override suspend fun setSubmissionErrorMessage(submissionId: String,
      errorMessage: String?) {
    updateField(collSubmissions, submissionId, ERROR_MESSAGE, errorMessage)
  }

  override suspend fun getSubmissionErrorMessage(submissionId: String) =
      getSubmissionField<String?>(submissionId, ERROR_MESSAGE)

  override suspend fun setSubmissionExecutionState(submissionId: String, state: JsonObject?) {
    writeGridFSDocument(bucketExecutionStates, submissionId, state)
  }

  override suspend fun getSubmissionExecutionState(submissionId: String): JsonObject? {
    val buf = readGridFSDocument(bucketExecutionStates, submissionId)
    return buf?.let { JsonObject(String(it.array())) }
  }

  override suspend fun addProcessChains(processChains: Collection<ProcessChain>,
      submissionId: String, status: ProcessChainStatus) {
    val submissionCount = collSubmissions.countDocumentsAwait(json {
      obj(
          INTERNAL_ID to submissionId
      )
    })
    if (submissionCount == 0L) {
      throw NoSuchElementException("There is no submission with ID `$submissionId'")
    }

    val sequence = getNextSequence(COLL_PROCESS_CHAINS, processChains.size)
    val requests = processChains.mapIndexed { i, pc ->
      writeGridFSDocument(bucketProcessChains, pc.id, JsonUtils.toJson(pc))
      val doc = json {
        obj(
            INTERNAL_ID to pc.id,
            SEQUENCE to sequence + i,
            SUBMISSION_ID to submissionId,
            STATUS to status.toString()
        )
      }
      InsertOneModel(doc)
    }

    collProcessChains.bulkWriteAwait(requests)
  }

  /**
   * Handle process chain metadata [document] and read corresponding process
   * chain from GridFS. Return pair of process chain and submission ID
   */
  private suspend fun readProcessChain(document: JsonObject): Pair<ProcessChain, String> {
    val submissionId = document.getString(SUBMISSION_ID, "")
    val id = document.getString(INTERNAL_ID)

    val buf = readGridFSDocument(bucketProcessChains, id)
        ?: throw IllegalStateException("Got process chain metadata with " +
            "ID `$id' but could not find corresponding object in GridFS bucket.")
    return Pair(JsonUtils.mapper.readValue(buf.array()), submissionId)
  }

  override suspend fun findProcessChains(size: Int, offset: Int, order: Int) =
      collProcessChains.findAwait(JsonObject(), size, offset,
          json {
            obj(
                SEQUENCE to order
            )
          }, PROCESS_CHAIN_EXCLUDES_BUT_SUBMISSION_ID)
          .map { readProcessChain(it) }

  override suspend fun findProcessChainsBySubmissionId(submissionId: String,
      size: Int, offset: Int, order: Int) =
      collProcessChains.findAwait(json {
        obj(
            SUBMISSION_ID to submissionId
        )
      }, size, offset, json {
        obj(
            SEQUENCE to order
        )
      }, PROCESS_CHAIN_EXCLUDES)
          .map { readProcessChain(it).first }

  override suspend fun findProcessChainIdsBySubmissionIdAndStatus(
      submissionId: String, status: ProcessChainStatus) =
      collProcessChains.findAwait(json {
        obj(
            SUBMISSION_ID to submissionId,
            STATUS to status.toString()
        )
      }, projection = json {
        obj(
            INTERNAL_ID to 1
        )
      }).map { it.getString(INTERNAL_ID) }

  override suspend fun findProcessChainStatusesBySubmissionId(submissionId: String) =
      collProcessChains.findAwait(json {
        obj(
            SUBMISSION_ID to submissionId
        )
      }, sort = json {
        obj(
            SEQUENCE to 1
        )
      }, projection = json {
        obj(
            INTERNAL_ID to 1,
            STATUS to 1
        )
      }).associateBy({ it.getString(INTERNAL_ID) }, {
        ProcessChainStatus.valueOf(it.getString(STATUS)) })

  override suspend fun findProcessChainById(processChainId: String): ProcessChain? {
    val doc = collProcessChains.findOneAwait(json {
      obj(
          INTERNAL_ID to processChainId
      )
    }, PROCESS_CHAIN_EXCLUDES)
    return doc?.let { readProcessChain(it).first }
  }

  override suspend fun countProcessChains() =
      collProcessChains.countDocumentsAwait(JsonObject())

  override suspend fun countProcessChainsBySubmissionId(submissionId: String) =
      collProcessChains.countDocumentsAwait(json {
        obj(
            SUBMISSION_ID to submissionId
        )
      })

  override suspend fun countProcessChainsByStatus(submissionId: String,
      status: ProcessChainStatus) =
      collProcessChains.countDocumentsAwait(json {
        obj(
            SUBMISSION_ID to submissionId,
            STATUS to status.toString()
        )
      })

  override suspend fun fetchNextProcessChain(currentStatus: ProcessChainStatus,
      newStatus: ProcessChainStatus): ProcessChain? {
    val doc = collProcessChains.findOneAndUpdateAwait(json {
      obj(
          STATUS to currentStatus.toString()
      )
    }, json {
      obj(
          "\$set" to obj(
              STATUS to newStatus.toString()
          )
      )
    }, FindOneAndUpdateOptions()
        .projection(wrap(PROCESS_CHAIN_EXCLUDES))
        .sort(wrap(json {
          obj(
              SEQUENCE to 1
          )
        })))
    return doc?.let { readProcessChain(it).first }
  }

  override suspend fun setProcessChainStartTime(processChainId: String, startTime: Instant?) {
    updateField(collProcessChains, processChainId, START_TIME, startTime)
  }

  override suspend fun getProcessChainStartTime(processChainId: String): Instant? =
      getProcessChainField<String?>(processChainId, START_TIME)?.let {
        Instant.from(ISO_INSTANT.parse(it))
      }

  override suspend fun setProcessChainEndTime(processChainId: String, endTime: Instant?) {
    updateField(collProcessChains, processChainId, END_TIME, endTime)
  }

  override suspend fun getProcessChainEndTime(processChainId: String): Instant? =
      getProcessChainField<String?>(processChainId, END_TIME)?.let {
        Instant.from(ISO_INSTANT.parse(it))
      }

  override suspend fun getProcessChainSubmissionId(processChainId: String): String =
      getProcessChainField(processChainId, SUBMISSION_ID)

  override suspend fun setProcessChainStatus(processChainId: String,
      status: ProcessChainStatus) {
    updateField(collProcessChains, processChainId, STATUS, status.toString())
  }

  override suspend fun setProcessChainStatus(processChainId: String,
      currentStatus: ProcessChainStatus, newStatus: ProcessChainStatus) {
    updateField(collProcessChains, processChainId, STATUS,
        currentStatus.toString(), newStatus.toString())
  }

  override suspend fun setAllProcessChainsStatus(submissionId: String,
      currentStatus: ProcessChainStatus, newStatus: ProcessChainStatus) {
    collProcessChains.updateManyAwait(json {
      obj(
          SUBMISSION_ID to submissionId,
          STATUS to currentStatus.toString()
      )
    }, json {
      obj(
          "\$set" to obj(
              STATUS to newStatus.toString()
          )
      )
    })
  }

  override suspend fun getProcessChainStatus(processChainId: String) =
      getProcessChainField<String>(processChainId, STATUS).let {
        ProcessChainStatus.valueOf(it)
      }

  override suspend fun setProcessChainResults(processChainId: String,
      results: Map<String, List<Any>>?) {
    writeGridFSDocument(bucketProcessChainResults, processChainId,
        results?.let{ JsonObject(it) })
  }

  override suspend fun getProcessChainResults(processChainId: String): Map<String, List<Any>>? {
    val processChainCount = collProcessChains.countDocumentsAwait(json {
      obj(
          INTERNAL_ID to processChainId
      )
    })
    if (processChainCount == 0L) {
      throw NoSuchElementException("There is no process chain with ID `$processChainId'")
    }

    val buf = readGridFSDocument(bucketProcessChainResults, processChainId)
    return buf?.let { JsonUtils.mapper.readValue(it.array()) }
  }

  override suspend fun setProcessChainErrorMessage(processChainId: String, errorMessage: String?) {
    updateField(collProcessChains, processChainId, ERROR_MESSAGE, errorMessage)
  }

  override suspend fun getProcessChainErrorMessage(processChainId: String): String? =
      getProcessChainField(processChainId, ERROR_MESSAGE)
}
