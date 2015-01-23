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

package org.apache.spark.scheduler

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

import akka.actor.{PoisonPill, ActorRef, Actor}

import org.apache.spark.{SparkConf, Logging}
import org.apache.spark.util.{AkkaUtils, ActorLogReceive}

private[spark] sealed trait OutputCommitCoordinationMessage extends Serializable

private[spark] case class StageStarted(stage: Int) extends OutputCommitCoordinationMessage
private[spark] case class StageEnded(stage: Int) extends OutputCommitCoordinationMessage
private[spark] case object StopCoordinator extends OutputCommitCoordinationMessage

private[spark] case class AskPermissionToCommitOutput(
    stage: Int,
    task: Long,
    taskAttempt: Long)
    extends OutputCommitCoordinationMessage

private[spark] case class TaskCompleted(
    stage: Int,
    task: Long,
    attempt: Long,
    successful: Boolean)
    extends OutputCommitCoordinationMessage

/**
 * Authority that decides whether tasks can commit output to HDFS.
 *
 * This lives on the driver, but the actor allows the tasks that commit
 * to Hadoop to invoke it.
 */
private[spark] class OutputCommitCoordinator(conf: SparkConf) extends Logging {

  // Initialized by SparkEnv
  var coordinatorActor: Option[ActorRef] = None
  private val timeout = AkkaUtils.askTimeout(conf)
  private val maxAttempts = AkkaUtils.numRetries(conf)
  private val retryInterval = AkkaUtils.retryWaitMs(conf)

  private type StageId = Int
  private type TaskId = Long
  private type TaskAttemptId = Long

  private val authorizedCommittersByStage:
  mutable.Map[StageId, mutable.Map[TaskId, TaskAttemptId]] = mutable.HashMap()

  def stageStart(stage: StageId) {
    sendToActor(StageStarted(stage))
  }
  def stageEnd(stage: StageId) {
    sendToActor(StageEnded(stage))
  }

  def canCommit(
      stage: StageId,
      task: TaskId,
      attempt: TaskAttemptId): Boolean = {
    askActor(AskPermissionToCommitOutput(stage, task, attempt))
  }

  def taskCompleted(
      stage: StageId,
      task: TaskId,
      attempt: TaskAttemptId,
      successful: Boolean) {
    sendToActor(TaskCompleted(stage, task, attempt, successful))
  }

  def stop() {
    sendToActor(StopCoordinator)
    coordinatorActor = None
    authorizedCommittersByStage.foreach(_._2.clear)
    authorizedCommittersByStage.clear
  }

  private def handleStageStart(stage: StageId): Unit = {
    authorizedCommittersByStage(stage) = mutable.HashMap[TaskId, TaskAttemptId]()
  }

  private def handleStageEnd(stage: StageId): Unit = {
    authorizedCommittersByStage.remove(stage)
  }

  private def handleAskPermissionToCommit(
      stage: StageId,
      task: TaskId,
      attempt: TaskAttemptId):
      Boolean = {
    if (!authorizedCommittersByStage.contains(stage)) {
      logDebug(s"Stage $stage has completed, so not allowing task attempt $attempt to commit")
      return false
    }
    val authorizedCommitters = authorizedCommittersByStage(stage)
    if (authorizedCommitters.contains(task)) {
      val existingCommitter = authorizedCommitters(task)
      logDebug(s"Denying $attempt to commit for stage=$stage, task=$task; " +
        s"existingCommitter = $existingCommitter")
      false
    } else {
      logDebug(s"Authorizing $attempt to commit for stage=$stage, task=$task")
      authorizedCommitters(task) = attempt
      true
    }
  }

  private def handleTaskCompletion(
      stage: StageId,
      task: TaskId,
      attempt: TaskAttemptId,
      successful: Boolean): Unit = {
    if (!authorizedCommittersByStage.contains(stage)) {
      logDebug(s"Ignoring task completion for completed stage")
      return
    }
    val authorizedCommitters = authorizedCommittersByStage(stage)
    if (authorizedCommitters.get(task) == Some(attempt) && !successful) {
      logDebug(s"Authorized committer $attempt (stage=$stage, task=$task) failed; clearing lock")
      // The authorized committer failed; clear the lock so future attempts can commit their output
      authorizedCommitters.remove(task)
    }
  }

  private def sendToActor(msg: OutputCommitCoordinationMessage) {
    coordinatorActor.foreach(_ ! msg)
  }

  private def askActor(msg: OutputCommitCoordinationMessage): Boolean = {
    coordinatorActor
      .map(AkkaUtils.askWithReply[Boolean](msg, _, maxAttempts, retryInterval, timeout))
      .getOrElse(false)
  }
}

private[spark] object OutputCommitCoordinator {

  class OutputCommitCoordinatorActor(outputCommitCoordinator: OutputCommitCoordinator)
    extends Actor with ActorLogReceive with Logging {

    override def receiveWithLogging = {
      case StageStarted(stage) =>
        outputCommitCoordinator.handleStageStart(stage)
      case StageEnded(stage) =>
        outputCommitCoordinator.handleStageEnd(stage)
      case AskPermissionToCommitOutput(stage, task, taskAttempt) =>
        sender ! outputCommitCoordinator.handleAskPermissionToCommit(stage, task, taskAttempt)
      case TaskCompleted(stage, task, attempt, successful) =>
        outputCommitCoordinator.handleTaskCompletion(stage, task, attempt, successful)
      case StopCoordinator =>
        logInfo("OutputCommitCoordinator stopped!")
        context.stop(self)
        sender ! true
    }
  }
  def createActor(coordinator: OutputCommitCoordinator): OutputCommitCoordinatorActor = {
    new OutputCommitCoordinatorActor(coordinator)
  }
}