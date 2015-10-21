package com.github.agourlay.cornichon.core

import cats.data.Xor
import cats.data.Xor.{ left, right }

import scala.Console._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.Duration
import scala.util._

class Engine {

  def runScenario(scenario: Scenario)(session: Session): ScenarioReport = {
    val initLogs = Seq(DefaultLogInstruction(s"Scenario : ${scenario.name}"))
    runSteps(scenario.steps, session, EventuallyConf.empty, None, initLogs) match {
      case s @ SuccessRunSteps(_, _)   ⇒ SuccessScenarioReport(scenario, s)
      case f @ FailedRunSteps(_, _, _) ⇒ FailedScenarioReport(scenario, f)
    }
  }

  private[cornichon] def runSteps(steps: Seq[Step], session: Session, eventuallyConf: EventuallyConf, snapshot: Option[RollbackSnapshot], logs: Seq[LogInstruction]): StepsReport =
    steps.headOption.fold[StepsReport](SuccessRunSteps(session, logs)) {
      case DebugStep(message) ⇒
        runSteps(steps.tail, session, eventuallyConf, snapshot, logs :+ ColoredLogInstruction(message(session), CYAN))

      case e @ EventuallyStart(conf) ⇒
        val updatedLogs = logs :+ DefaultLogInstruction(s"   ${e.title}")
        runSteps(steps.tail, session, conf, Some(RollbackSnapshot(steps.tail, session)), updatedLogs)

      case EventuallyStop(conf) ⇒
        val updatedLogs = logs :+ DefaultLogInstruction(s"   Eventually bloc succeeded in ${conf.maxTime.toSeconds - eventuallyConf.maxTime.toSeconds} sec.")
        runSteps(steps.tail, session, EventuallyConf.empty, None, updatedLogs)

      case ConcurrentStop(factor) ⇒
        runSteps(steps.tail, session, EventuallyConf.empty, None, logs)

      case c @ ConcurrentStart(factor, maxTime) ⇒
        val updatedLogs = logs :+ DefaultLogInstruction(s"   ${c.title}")
        val concurrentSteps = findEnclosedSteps(c, steps.tail)
        if (concurrentSteps.isEmpty) {
          val updatedLogs = logs ++ logStepErrorResult(s"   ${c.title}", MalformedConcurrentBloc, RED) ++ logNonExecutedStep(steps.tail)
          buildFailedRunSteps(steps, c, MalformedConcurrentBloc, updatedLogs)
        } else {
          val now = System.nanoTime
          val results = Await.result(
            Future.traverse(List.fill(factor)(concurrentSteps)) { steps ⇒
              Future { runSteps(steps, session, eventuallyConf, None, updatedLogs) }
            }, maxTime
          )
          val executionTime = Duration.fromNanos(System.nanoTime - now)
          val (successStepsRun, failedStepsRun) =
            (
              results.collect { case s @ SuccessRunSteps(_, _) ⇒ s },
              results.collect { case f @ FailedRunSteps(_, _, _) ⇒ f }
            )

          if (failedStepsRun.isEmpty) {
            val updatedSession = successStepsRun.head.session
            val updatedLogs = successStepsRun.head.logs :+ DefaultLogInstruction(s"   Concurrently bloc with factor '$factor' succeeded in ${executionTime.toMillis} millis.")
            runSteps(steps.tail.drop(concurrentSteps.size), updatedSession, eventuallyConf, None, updatedLogs)
          } else
            failedStepsRun.head.copy(logs = failedStepsRun.head.logs ++ logNonExecutedStep(steps.tail))
        }

      case execStep: ExecutableStep[_] ⇒
        val now = System.nanoTime
        val stepResult = runStepAction(execStep)(session)
        val executionTime = Duration.fromNanos(System.nanoTime - now)
        stepResult match {
          case Xor.Left(e) ⇒
            val remainingTime = eventuallyConf.maxTime - executionTime
            if (remainingTime.gt(Duration.Zero)) {
              val updatedLogs = logs ++ logStepErrorResult(execStep.title, e, CYAN)
              Thread.sleep(eventuallyConf.interval.toMillis)
              runSteps(snapshot.get.steps, snapshot.get.session, eventuallyConf.consume(executionTime + eventuallyConf.interval), snapshot, updatedLogs)
            } else {
              val updatedLogs = logs ++ logStepErrorResult(execStep.title, e, RED) ++ logNonExecutedStep(steps.tail)
              buildFailedRunSteps(steps, execStep, e, updatedLogs)
            }

          case Xor.Right(currentSession) ⇒
            val updatedLogs = if (execStep.show)
              logs :+ ColoredLogInstruction(s"   ${execStep.title}", GREEN)
            else logs
            runSteps(steps.tail, currentSession, eventuallyConf.consume(executionTime), snapshot, updatedLogs)
        }
    }

  private[cornichon] def runStepAction[A](step: ExecutableStep[A])(implicit session: Session): Xor[CornichonError, Session] =
    Try { step.action(session) } match {
      case Success((newSession, stepAssertion)) ⇒ runStepPredicate(step.negate, newSession, stepAssertion)
      case Failure(e) ⇒
        e match {
          case ce: CornichonError ⇒ left(ce)
          case _                  ⇒ left(StepExecutionError(e))
        }
    }

  private[cornichon] def runStepPredicate[A](negateStep: Boolean, newSession: Session, stepAssertion: StepAssertion[A]): Xor[CornichonError, Session] = {
    val succeedAsExpected = stepAssertion.isSuccess && !negateStep
    val failedAsExpected = !stepAssertion.isSuccess && negateStep

    if (succeedAsExpected || failedAsExpected) right(newSession)
    else
      stepAssertion match {
        case SimpleStepAssertion(expected, actual) ⇒
          left(StepAssertionError(expected, actual, negateStep))
        case DetailedStepAssertion(expected, actual, details) ⇒
          left(DetailedStepAssertionError(actual, details))
      }
  }

  // TODO take care of nested blocs and do not just pick the first closing element
  private[cornichon] def findEnclosedSteps(openingStep: Step, steps: Seq[Step]): Seq[Step] = {
    def predicate(openingStep: Step): Step ⇒ Boolean = s ⇒ openingStep match {
      case ConcurrentStart(_, _) ⇒ !s.isInstanceOf[ConcurrentStop]
      case EventuallyStart(_)    ⇒ !s.isInstanceOf[EventuallyStop] // Not used yet
      case _                     ⇒ false
    }
    steps.takeWhile(s ⇒ predicate(openingStep)(s))
  }

  private[cornichon] def logStepErrorResult(stepTitle: String, error: CornichonError, ansiColor: String): Seq[LogInstruction] =
    Seq(ColoredLogInstruction(s"   $stepTitle *** FAILED ***", ansiColor)) ++ error.msg.split('\n').map { m ⇒
      ColoredLogInstruction(s"   $m", ansiColor)
    }

  private[cornichon] def logNonExecutedStep(steps: Seq[Step]): Seq[LogInstruction] =
    steps.collect { case e: ExecutableStep[_] ⇒ e }
      .filter(_.show).map { step ⇒
        ColoredLogInstruction(s"   ${step.title}", CYAN)
      }

  private[cornichon] def buildFailedRunSteps(steps: Seq[Step], currentStep: Step, e: CornichonError, logs: Seq[LogInstruction]): FailedRunSteps = {
    val failedStep = FailedStep(currentStep, e)
    val notExecutedStep = steps.tail.collect { case ExecutableStep(title, _, _, _) ⇒ title }
    FailedRunSteps(failedStep, notExecutedStep, logs)
  }

  private[cornichon] case class RollbackSnapshot(steps: Seq[Step], session: Session)
}