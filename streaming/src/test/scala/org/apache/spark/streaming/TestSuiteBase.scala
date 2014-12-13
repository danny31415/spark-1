/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.streaming

import java.io.{ObjectInputStream, IOException}
import java.util.concurrent.TimeoutException

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.SynchronizedBuffer
import scala.concurrent.duration.{Duration => SDuration}
import scala.reflect.ClassTag

import org.scalatest.{BeforeAndAfter, FunSuite}

import org.apache.spark.streaming.dstream.{DStream, InputDStream, ForEachDStream}
import org.apache.spark.streaming.scheduler.{StreamingListenerBatchCompleted, StreamingListener}
import org.apache.spark.streaming.util.ManualClock
import org.apache.spark.{SparkConf, Logging}
import org.apache.spark.rdd.RDD
import org.apache.spark.util.Utils

/**
 * This is a input stream just for the testsuites. This is equivalent to a checkpointable,
 * replayable, reliable message queue like Kafka. It requires a sequence as input, and
 * returns the i_th element at the i_th batch unde manual clock.
 */
class TestInputStream[T: ClassTag](ssc_ : StreamingContext, input: Seq[Seq[T]], numPartitions: Int)
  extends InputDStream[T](ssc_) {

  def start() {}

  def stop() {}

  def compute(validTime: Time): Option[RDD[T]] = {
    logInfo("Computing RDD for time " + validTime)
    val index = ((validTime - zeroTime) / slideDuration - 1).toInt
    val selectedInput = if (index < input.size) input(index) else Seq[T]()

    // lets us test cases where RDDs are not created
    if (selectedInput == null)
      return None

    val rdd = ssc.sc.makeRDD(selectedInput, numPartitions)
    logInfo("Created RDD " + rdd.id + " with " + selectedInput)
    Some(rdd)
  }
}

/**
 * This is a output stream just for the testsuites. All the output is collected into a
 * ArrayBuffer. This buffer is wiped clean on being restored from checkpoint.
 *
 * The buffer contains a sequence of RDD's, each containing a sequence of items
 */
class TestOutputStream[T: ClassTag](parent: DStream[T],
    val output: ArrayBuffer[Seq[T]] = ArrayBuffer[Seq[T]]())
  extends ForEachDStream[T](parent, (rdd: RDD[T], t: Time) => {
    val collected = rdd.collect()
    output += collected
  }) {

  // This is to clear the output buffer every it is read from a checkpoint
  @throws(classOf[IOException])
  private def readObject(ois: ObjectInputStream): Unit = Utils.tryOrIOException {
    ois.defaultReadObject()
    output.clear()
  }
}

/**
 * This is a output stream just for the testsuites. All the output is collected into a
 * ArrayBuffer. This buffer is wiped clean on being restored from checkpoint.
 *
 * The buffer contains a sequence of RDD's, each containing a sequence of partitions, each
 * containing a sequence of items.
 */
class TestOutputStreamWithPartitions[T: ClassTag](parent: DStream[T],
    val output: ArrayBuffer[Seq[Seq[T]]] = ArrayBuffer[Seq[Seq[T]]]())
  extends ForEachDStream[T](parent, (rdd: RDD[T], t: Time) => {
    val collected = rdd.glom().collect().map(_.toSeq)
    output += collected
  }) {

  // This is to clear the output buffer every it is read from a checkpoint
  @throws(classOf[IOException])
  private def readObject(ois: ObjectInputStream): Unit = Utils.tryOrIOException {
    ois.defaultReadObject()
    output.clear()
  }

  def toTestOutputStream = new TestOutputStream[T](this.parent, this.output.map(_.flatten))
}

/**
 * This is an interface that can be used to block until certain events occur, such as
 * the start/completion of batches.  This is much less brittle than waiting on wall-clock time.
 * Internally, this is implemented using a StreamingListener.  Constructing a new instance of this
 * class automatically registers a StreamingListener on the given StreamingContext.
 */
  class StreamingTestWaiter(ssc: StreamingContext) {

  // All access to this state should be guarded by `StreamingListener.this.synchronized`
  private var numCompletedBatches = 0

  private val listener = new StreamingListener {
    override def onBatchCompleted(batchCompleted: StreamingListenerBatchCompleted): Unit =
      StreamingTestWaiter.this.synchronized {
        numCompletedBatches += 1
        StreamingTestWaiter.this.notifyAll()
      }
  }
  ssc.addStreamingListener(listener)

  def getNumCompletedBatches: Int = this.synchronized {
    numCompletedBatches
  }

  /**
   * Block until the number of completed batches reaches the given threshold.
   */
  def waitForTotalBatchesCompleted(
      targetNumBatches: Int,
      timeout: SDuration = SDuration.Inf): Unit = this.synchronized {
    val startTime = System.nanoTime
    def timedOut = timeout < SDuration.Inf && (System.nanoTime - startTime) >= timeout.toNanos
    def successful = getNumCompletedBatches >= targetNumBatches
    while (!timedOut && !successful) {
      this.wait(timeout.toMillis)
    }
    if (!successful && timedOut) {
      throw new TimeoutException(s"Waited for $targetNumBatches completed batches, but only" +
      s" $numCompletedBatches have completed after $timeout")
    }
  }
}

/**
 * This is the base trait for Spark Streaming testsuites. This provides basic functionality
 * to run user-defined set of input on user-defined stream operations, and verify the output.
 */
trait TestSuiteBase extends FunSuite with BeforeAndAfter with Logging {

  // Name of the framework for Spark context
  def framework = this.getClass.getSimpleName

  // Master for Spark context
  def master = "local[2]"

  // Batch duration
  def batchDuration = Seconds(1)

  // Directory where the checkpoint data will be saved
  lazy val checkpointDir = {
    val dir = Utils.createTempDir()
    logDebug(s"checkpointDir: $dir")
    dir.toString
  }

  // Number of partitions of the input parallel collections created for testing
  def numInputPartitions = 2

  // Maximum time to wait before the test times out
  def maxWaitTimeMillis = 10000

  // Whether to use manual clock or not
  def useManualClock = true

  // Whether to actually wait in real time before changing manual clock
  def actuallyWait = false

  //// A SparkConf to use in tests. Can be modified before calling setupStreams to configure things.
  val conf = new SparkConf()
    .setMaster(master)
    .setAppName(framework)

  // Default before function for any streaming test suite. Override this
  // if you want to add your stuff to "before" (i.e., don't call before { } )
  def beforeFunction() {
    if (useManualClock) {
      logInfo("Using manual clock")
      conf.set("spark.streaming.clock", "org.apache.spark.streaming.util.ManualClock")
    } else {
      logInfo("Using real clock")
      conf.set("spark.streaming.clock", "org.apache.spark.streaming.util.SystemClock")
    }
  }

  // Default after function for any streaming test suite. Override this
  // if you want to add your stuff to "after" (i.e., don't call after { } )
  def afterFunction() {
    System.clearProperty("spark.streaming.clock")
  }

  before(beforeFunction)
  after(afterFunction)

  /**
   * Run a block of code with the given StreamingContext and automatically
   * stop the context when the block completes or when an exception is thrown.
   */
  def withStreamingContext[R](ssc: StreamingContext)(block: StreamingContext => R): R = {
    try {
      block(ssc)
    } finally {
      try {
        ssc.stop(stopSparkContext = true)
      } catch {
        case e: Exception =>
          logError("Error stopping StreamingContext", e)
      }
    }
  }

  /**
   * Run a block of code with the given TestServer and automatically
   * stop the server when the block completes or when an exception is thrown.
   */
  def withTestServer[R](testServer: TestServer)(block: TestServer => R): R = {
    try {
      block(testServer)
    } finally {
      try {
        testServer.stop()
      } catch {
        case e: Exception =>
          logError("Error stopping TestServer", e)
      }
    }
  }

  /**
   * Set up required DStreams to test the DStream operation using the two sequences
   * of input collections.
   */
  def setupStreams[U: ClassTag, V: ClassTag](
      input: Seq[Seq[U]],
      operation: DStream[U] => DStream[V],
      numPartitions: Int = numInputPartitions
    ): StreamingContext = {
    // Create StreamingContext
    val ssc = new StreamingContext(conf, batchDuration)
    if (checkpointDir != null) {
      ssc.checkpoint(checkpointDir)
    }

    // Setup the stream computation
    val inputStream = new TestInputStream(ssc, input, numPartitions)
    val operatedStream = operation(inputStream)
    val outputStream = new TestOutputStreamWithPartitions(operatedStream,
      new ArrayBuffer[Seq[Seq[V]]] with SynchronizedBuffer[Seq[Seq[V]]])
    outputStream.register()
    ssc
  }

  /**
   * Set up required DStreams to test the binary operation using the sequence
   * of input collections.
   */
  def setupStreams[U: ClassTag, V: ClassTag, W: ClassTag](
      input1: Seq[Seq[U]],
      input2: Seq[Seq[V]],
      operation: (DStream[U], DStream[V]) => DStream[W]
    ): StreamingContext = {
    // Create StreamingContext
    val ssc = new StreamingContext(conf, batchDuration)
    if (checkpointDir != null) {
      ssc.checkpoint(checkpointDir)
    }

    // Setup the stream computation
    val inputStream1 = new TestInputStream(ssc, input1, numInputPartitions)
    val inputStream2 = new TestInputStream(ssc, input2, numInputPartitions)
    val operatedStream = operation(inputStream1, inputStream2)
    val outputStream = new TestOutputStreamWithPartitions(operatedStream,
      new ArrayBuffer[Seq[Seq[W]]] with SynchronizedBuffer[Seq[Seq[W]]])
    outputStream.register()
    ssc
  }

  /**
   * Runs the streams set up in `ssc` on manual clock for `numBatches` batches and
   * returns the collected output. It will wait until `numExpectedOutput` number of
   * output data has been collected or timeout (set by `maxWaitTimeMillis`) is reached.
   *
   * Returns a sequence of items for each RDD.
   */
  def runStreams[V: ClassTag](
      ssc: StreamingContext,
      numBatches: Int,
      numExpectedOutput: Int
    ): Seq[Seq[V]] = {
    // Flatten each RDD into a single Seq
    runStreamsWithPartitions(ssc, numBatches, numExpectedOutput).map(_.flatten.toSeq)
  }

  /**
   * Runs the streams set up in `ssc` on manual clock for `numBatches` batches and
   * returns the collected output. It will wait until `numExpectedOutput` number of
   * output data has been collected or timeout (set by `maxWaitTimeMillis`) is reached.
   *
   * Returns a sequence of RDD's. Each RDD is represented as several sequences of items, each
   * representing one partition.
   */
  def runStreamsWithPartitions[V: ClassTag](
      ssc: StreamingContext,
      numBatches: Int,
      numExpectedOutput: Int
    ): Seq[Seq[Seq[V]]] = {
    assert(numBatches > 0, "Number of batches to run stream computation is zero")
    assert(numExpectedOutput > 0, "Number of expected outputs after " + numBatches + " is zero")
    logInfo("numBatches = " + numBatches + ", numExpectedOutput = " + numExpectedOutput)

    // Get the output buffer
    val outputStream = ssc.graph.getOutputStreams.
      filter(_.isInstanceOf[TestOutputStreamWithPartitions[_]]).
      head.asInstanceOf[TestOutputStreamWithPartitions[V]]
    val output = outputStream.output

    try {
      // Start computation
      ssc.start()

      // Advance manual clock
      val clock = ssc.scheduler.clock.asInstanceOf[ManualClock]
      logInfo("Manual clock before advancing = " + clock.time)
      if (actuallyWait) {
        for (i <- 1 to numBatches) {
          logInfo("Actually waiting for " + batchDuration)
          clock.addToTime(batchDuration.milliseconds)
          Thread.sleep(batchDuration.milliseconds)
        }
      } else {
        clock.addToTime(numBatches * batchDuration.milliseconds)
      }
      logInfo("Manual clock after advancing = " + clock.time)

      // Wait until expected number of output items have been generated
      val startTime = System.currentTimeMillis()
      while (output.size < numExpectedOutput && System.currentTimeMillis() - startTime < maxWaitTimeMillis) {
        logInfo("output.size = " + output.size + ", numExpectedOutput = " + numExpectedOutput)
        ssc.awaitTermination(50)
      }
      val timeTaken = System.currentTimeMillis() - startTime
      logInfo("Output generated in " + timeTaken + " milliseconds")
      output.foreach(x => logInfo("[" + x.mkString(",") + "]"))
      assert(timeTaken < maxWaitTimeMillis, "Operation timed out after " + timeTaken + " ms")
      assert(output.size === numExpectedOutput, "Unexpected number of outputs generated")

      Thread.sleep(100) // Give some time for the forgetting old RDDs to complete
    } finally {
      ssc.stop(stopSparkContext = true)
    }
    output
  }

  /**
   * Verify whether the output values after running a DStream operation
   * is same as the expected output values, by comparing the output
   * collections either as lists (order matters) or sets (order does not matter)
   */
  def verifyOutput[V: ClassTag](
      output: Seq[Seq[V]],
      expectedOutput: Seq[Seq[V]],
      useSet: Boolean
    ) {
    logInfo("--------------------------------")
    logInfo("output.size = " + output.size)
    logInfo("output")
    output.foreach(x => logInfo("[" + x.mkString(",") + "]"))
    logInfo("expected output.size = " + expectedOutput.size)
    logInfo("expected output")
    expectedOutput.foreach(x => logInfo("[" + x.mkString(",") + "]"))
    logInfo("--------------------------------")

    // Match the output with the expected output
    assert(output.size === expectedOutput.size, "Number of outputs do not match")
    for (i <- 0 until output.size) {
      if (useSet) {
        assert(output(i).toSet === expectedOutput(i).toSet)
      } else {
        assert(output(i).toList === expectedOutput(i).toList)
      }
    }
    logInfo("Output verified successfully")
  }

  /**
   * Test unary DStream operation with a list of inputs, with number of
   * batches to run same as the number of expected output values
   */
  def testOperation[U: ClassTag, V: ClassTag](
      input: Seq[Seq[U]],
      operation: DStream[U] => DStream[V],
      expectedOutput: Seq[Seq[V]],
      useSet: Boolean = false
    ) {
    testOperation[U, V](input, operation, expectedOutput, -1, useSet)
  }

  /**
   * Test unary DStream operation with a list of inputs
   * @param input      Sequence of input collections
   * @param operation  Binary DStream operation to be applied to the 2 inputs
   * @param expectedOutput Sequence of expected output collections
   * @param numBatches Number of batches to run the operation for
   * @param useSet     Compare the output values with the expected output values
   *                   as sets (order matters) or as lists (order does not matter)
   */
  def testOperation[U: ClassTag, V: ClassTag](
      input: Seq[Seq[U]],
      operation: DStream[U] => DStream[V],
      expectedOutput: Seq[Seq[V]],
      numBatches: Int,
      useSet: Boolean
    ) {
    val numBatches_ = if (numBatches > 0) numBatches else expectedOutput.size
    withStreamingContext(setupStreams[U, V](input, operation)) { ssc =>
      val output = runStreams[V](ssc, numBatches_, expectedOutput.size)
      verifyOutput[V](output, expectedOutput, useSet)
    }
  }

  /**
   * Test binary DStream operation with two lists of inputs, with number of
   * batches to run same as the number of expected output values
   */
  def testOperation[U: ClassTag, V: ClassTag, W: ClassTag](
      input1: Seq[Seq[U]],
      input2: Seq[Seq[V]],
      operation: (DStream[U], DStream[V]) => DStream[W],
      expectedOutput: Seq[Seq[W]],
      useSet: Boolean
    ) {
    testOperation[U, V, W](input1, input2, operation, expectedOutput, -1, useSet)
  }

  /**
   * Test binary DStream operation with two lists of inputs
   * @param input1     First sequence of input collections
   * @param input2     Second sequence of input collections
   * @param operation  Binary DStream operation to be applied to the 2 inputs
   * @param expectedOutput Sequence of expected output collections
   * @param numBatches Number of batches to run the operation for
   * @param useSet     Compare the output values with the expected output values
   *                   as sets (order matters) or as lists (order does not matter)
   */
  def testOperation[U: ClassTag, V: ClassTag, W: ClassTag](
      input1: Seq[Seq[U]],
      input2: Seq[Seq[V]],
      operation: (DStream[U], DStream[V]) => DStream[W],
      expectedOutput: Seq[Seq[W]],
      numBatches: Int,
      useSet: Boolean
    ) {
    val numBatches_ = if (numBatches > 0) numBatches else expectedOutput.size
    withStreamingContext(setupStreams[U, V, W](input1, input2, operation)) { ssc =>
      val output = runStreams[W](ssc, numBatches_, expectedOutput.size)
      verifyOutput[W](output, expectedOutput, useSet)
    }
  }
}
