package com.typesafe.sbt.jse

import sbt._
import sbt.Keys._
import com.typesafe.sbt.web.incremental.OpInputHasher
import spray.json._
import com.typesafe.sbt.web._
import xsbti.{Problem, Severity}
import com.typesafe.sbt.web.incremental.OpResult
import com.typesafe.sbt.web.incremental.OpFailure
import com.typesafe.sbt.jse.SbtJsEnginePlugin.JsEngineKeys
import com.typesafe.sbt.web.incremental.OpInputHash
import akka.actor.ActorRef
import akka.util.Timeout
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.collection.immutable
import com.typesafe.jse._
import com.typesafe.jse.Node
import com.typesafe.sbt.web.SbtWebPlugin._
import akka.pattern.ask
import scala.concurrent.duration.FiniteDuration
import com.typesafe.sbt.web.incremental
import com.typesafe.sbt.web.CompileProblems
import com.typesafe.jse.Engine.JsExecutionResult
import com.typesafe.sbt.web.incremental.OpSuccess
import sbt.Configuration
import sbinary.{Input, Output, Format}

/**
 * The commonality of JS task execution oriented plugins is captured by this class.
 */
object SbtJsTaskPlugin {

  object JsTaskKeys {

    val fileFilter = SettingKey[FileFilter]("jstask-file-filter", "The file extension of files to perform a task on.")
    val fileInputHasher = TaskKey[OpInputHasher[File]]("jstask-file-input-hasher", "A function that constitues a change for a given file.")
    val jsOptions = TaskKey[String]("jstask-js-options", "The JSON options to be passed to the task.")
    val taskMessage = SettingKey[String]("jstask-message", "The message to output for a task")
    val shellFile = SettingKey[String]("jstask-shell-file", "The name of the file to perform a given task.")
    val shellSource = TaskKey[File]("jstask-shell-source", "The target location of the js shell script to use.")
    val timeoutPerSource = SettingKey[FiniteDuration]("jstask-timeout-per-source", "The maximum number of seconds to wait per source file processed by the JS task.")
  }

  import JsTaskKeys._

  import scala.concurrent.duration._

  val jsTaskSettings = Seq(
    timeoutPerSource := 30.seconds
  )

  val jsEngineAndTaskSettings = SbtJsEnginePlugin.jsEngineSettings ++ jsTaskSettings

  /**
   * Thrown when there is an unexpected problem to do with the task's execution.
   */
  class JsTaskFailure(m: String) extends RuntimeException(m)

  /**
   * For automatic transformation of Json structures.
   */
  object JsTaskProtocol extends DefaultJsonProtocol {

    implicit object FileFormat extends JsonFormat[File] {
      def write(f: File) = JsString(f.getCanonicalPath)

      def read(value: JsValue) = value match {
        case s: JsString => new File(s.convertTo[String])
        case x => deserializationError(s"String expected for a file, instead got $x")
      }
    }

    implicit object PathMappingFormat extends JsonFormat[PathMapping] {
      def write(p: PathMapping) = JsArray(JsString(p._1.getCanonicalPath), JsString(p._2))

      def read(value: JsValue) = value match {
        case a: JsArray => FileFormat.read(a.elements(0)) -> a.elements(1).convertTo[String]
        case x => deserializationError(s"Array expected for a path mapping, instead got $x")
      }
    }

    implicit val opSuccessFormat = jsonFormat2(OpSuccess)

    implicit object LineBasedProblemFormat extends JsonFormat[LineBasedProblem] {
      def write(p: LineBasedProblem) = JsString("unimplemented")

      def read(value: JsValue) = value match {
        case o: JsObject => new LineBasedProblem(
          o.fields.get("message").map(_.convertTo[String]).getOrElse("unknown message"),
          o.fields.get("severity").map {
            v =>
              v.toString() match {
                case "info" => Severity.Info
                case "warn" => Severity.Warn
                case _ => Severity.Error
              }
          }.getOrElse(Severity.Error),
          o.fields.get("lineNumber").map(_.convertTo[Int]).getOrElse(0),
          o.fields.get("characterOffset").map(_.convertTo[Int]).getOrElse(0),
          o.fields.get("lineContent").map(_.convertTo[String]).getOrElse("unknown line content"),
          o.fields.get("source").map(_.convertTo[File]).getOrElse(file(""))
        )
        case x => deserializationError(s"Object expected for the problem, instead got $x")
      }

    }

    implicit object OpResultFormat extends JsonFormat[OpResult] {

      def write(r: OpResult) = JsString("unimplemented")

      def read(value: JsValue) = value match {
        case o: JsObject => opSuccessFormat.read(o)
        case JsNull => OpFailure
        case x => deserializationError(s"Object expected for the op result, instead got $x")
      }
    }

    case class ProblemResultsPair(results: Seq[SourceResultPair], problems: Seq[LineBasedProblem])

    case class SourceResultPair(result: OpResult, source: PathMapping)

    implicit val sourceResultPairFormat = jsonFormat2(SourceResultPair)
    implicit val problemResultPairFormat = jsonFormat2(ProblemResultsPair)
  }

}

abstract class SbtJsTaskPlugin extends sbt.Plugin {

  import SbtJsTaskPlugin._

  import WebKeys._
  import JsEngineKeys._
  import SbtJsTaskPlugin.JsTaskKeys._

  val jsTaskSpecificUnscopedConfigSettings = Seq(
    fileInputHasher := OpInputHasher[File](f => OpInputHash.hashString(f.getAbsolutePath + "|" + jsOptions.value)),
    jsOptions := "{}",
    resourceManaged := target.value / moduleName.value
  )

  val jsTaskSpecificUnscopedSettings =
    inConfig(Assets)(jsTaskSpecificUnscopedConfigSettings) ++
      inConfig(TestAssets)(jsTaskSpecificUnscopedConfigSettings) ++
      Seq(
        shellSource := {
          SbtWebPlugin.copyResourceTo(
            (target in Plugin).value / moduleName.value,
            shellFile.value,
            SbtJsTaskPlugin.getClass.getClassLoader,
            streams.value.cacheDirectory / "copy-resource"
          )
        }
      )


  // node.js docs say *NOTHING* about what encoding is used when you write a string to stdout.
  // It seems that they have it hard coded to use UTF-8, some small tests I did indicate that changing the platform
  // encoding makes no difference on what encoding node uses when it writes strings to stdout.
  private val NodeEncoding = "UTF-8"
  // Used to signal when the script is sending back structured JSON data
  private val JsonEscapeChar: Char = 0x10

  private type FileOpResultMappings = Map[PathMapping, OpResult]

  private def FileOpResultMappings(s: (PathMapping, OpResult)*): FileOpResultMappings = Map(s: _*)

  private type FileWrittenAndProblems = (Seq[File], Seq[Problem])

  private def FilesWrittenAndProblems(): FileWrittenAndProblems = FilesWrittenAndProblems(Nil, Nil)

  private def FilesWrittenAndProblems(pathMappingsAndProblems: (Seq[File], Seq[Problem])): FileWrittenAndProblems = pathMappingsAndProblems

  private def engineTypeToProps(engineType: EngineType.Value, env: Map[String, String]) = {
    engineType match {
      case EngineType.CommonNode => CommonNode.props(stdEnvironment = env)
      case EngineType.Node => Node.props(stdEnvironment = env)
      case EngineType.PhantomJs => PhantomJs.props()
      case EngineType.Rhino => Rhino.props()
      case EngineType.Trireme => Trireme.props(stdEnvironment = env)
    }
  }


  private def executeJsOnEngine(engine: ActorRef, shellSource: File, args: Seq[String],
                                stderrSink: String => Unit, stdoutSink: String => Unit)
                               (implicit timeout: Timeout, ec: ExecutionContext): Future[Seq[JsValue]] = {

    (engine ? Engine.ExecuteJs(
      shellSource,
      args.to[immutable.Seq],
      timeout.duration
    )).mapTo[JsExecutionResult].map {
      result =>

      // Stuff below probably not needed once jsengine is refactored to stream this

      // Dump stderr as is
        if (!result.error.isEmpty) {
          stderrSink(new String(result.error.toArray, NodeEncoding))
        }

        // Split stdout into lines
        val outputLines = new String(result.output.toArray, NodeEncoding).split("\r?\n")

        // Iterate through lines, extracting out JSON messages, and printing the rest out
        val results = outputLines.foldLeft(Seq.empty[JsValue]) {
          (results, line) =>
            if (line.indexOf(JsonEscapeChar) == -1) {
              stdoutSink(line)
              results
            } else {
              val (out, json) = line.span(_ != JsonEscapeChar)
              if (!out.isEmpty) {
                stdoutSink(out)
              }
              results :+ JsonParser(json.drop(1))
            }
        }

        if (result.exitValue != 0) {
          throw new JsTaskFailure(new String(result.error.toArray, NodeEncoding))
        }
        results
    }

  }

  private def executeSourceFilesJs(
                                    engine: ActorRef,
                                    shellSource: File,
                                    sourceFileMappings: Seq[PathMapping],
                                    target: File,
                                    options: String,
                                    stderrSink: String => Unit,
                                    stdoutSink: String => Unit
                                    )(implicit timeout: Timeout): Future[(FileOpResultMappings, Seq[Problem])] = {

    import ExecutionContext.Implicits.global

    val args = immutable.Seq(
      JsArray(sourceFileMappings.map(x => JsArray(JsString(x._1.getCanonicalPath), JsString(x._2))).toList).toString(),
      target.getAbsolutePath,
      options
    )

    executeJsOnEngine(engine, shellSource, args, stderrSink, stdoutSink).map {
      results =>
        import JsTaskProtocol._
        val prp = results.foldLeft(ProblemResultsPair(Nil, Nil)) {
          (cumulative, result) =>
            val prp = result.convertTo[ProblemResultsPair]
            ProblemResultsPair(
              cumulative.results ++ prp.results,
              cumulative.problems ++ prp.problems
            )
        }
        (prp.results.map(sr => sr.source -> sr.result).toMap, prp.problems)
    }
  }

  /*
   * For reading/writing binary representations of files.
   */
  private implicit object FileFormat extends Format[File] {

    import Cache._

    def reads(in: Input): File = file(StringFormat.reads(in))

    def writes(out: Output, fh: File) = StringFormat.writes(out, fh.getAbsolutePath)
  }

  /**
   * Primary means of executing a JavaScript shell script for processing source files. unmanagedResources is assumed
   * to contain the source files to filter on.
   * @param task The task to resolve js task settings from - relates to the concrete plugin sub class
   * @param config The sbt configuration to use e.g. Assets or TestAssets
   * @return A task object
   */
  def jsSourceFileTask(
                        task: TaskKey[Seq[File]],
                        config: Configuration
                        ): Def.Initialize[Task[Seq[File]]] = Def.task {

    val engineProps = engineTypeToProps(engineType.value, NodeEngine.nodePathEnv(immutable.Seq((nodeModules in Plugin).value.getCanonicalPath)))

    val sources = ((unmanagedSources in config).value ** (fileFilter in task in config).value)
      .get.pair(relativeTo((unmanagedSources in config).value))

    val logger: Logger = state.value.log

    implicit val opInputHasher = (fileInputHasher in task in config).value
    val results: FileWrittenAndProblems = incremental.runIncremental(streams.value.cacheDirectory / "run", sources) {
      modifiedJsSources: Seq[PathMapping] =>

        if (modifiedJsSources.size > 0) {

          streams.value.log.info(s"${(taskMessage in task in config).value} on ${
            modifiedJsSources.size
          } source(s)")

          val resultBatches: Seq[Future[(FileOpResultMappings, Seq[Problem])]] =
            try {
              val sourceBatches = (modifiedJsSources grouped Math.max(modifiedJsSources.size / parallelism.value, 1)).toSeq
              sourceBatches.map {
                sourceBatch =>
                  implicit val timeout = Timeout((timeoutPerSource in task in config).value * sourceBatch.size)
                  withActorRefFactory(state.value, this.getClass.getName) {
                    arf =>
                      val engine = arf.actorOf(engineProps)
                      implicit val timeout = Timeout((timeoutPerSource in task in config).value * sourceBatch.size)
                      executeSourceFilesJs(
                        engine,
                        (shellSource in task in config).value,
                        sourceBatch,
                        (resourceManaged in task in config).value,
                        (jsOptions in task in config).value,
                        m => logger.error(m),
                        m => logger.info(m)
                      )
                  }
              }
            }

          import scala.concurrent.ExecutionContext.Implicits.global
          val pendingResults = Future.sequence(resultBatches)
          val completedResults = Await.result(pendingResults, (timeoutPerSource in task in config).value * modifiedJsSources.size)

          completedResults.foldLeft((FileOpResultMappings(), FilesWrittenAndProblems())) {
            (allCompletedResults, completedResult) =>

              val (prevOpResults, (prevFilesWritten, prevProblems)) = allCompletedResults

              val (nextOpResults, nextProblems) = completedResult
              val nextFilesWritten: Seq[File] = nextOpResults.values.map {
                case opSuccess: OpSuccess => opSuccess.filesWritten
                case _ => Nil
              }.flatten.toSeq

              (
                prevOpResults ++ nextOpResults,
                FilesWrittenAndProblems(prevFilesWritten ++ nextFilesWritten, prevProblems ++ nextProblems)
                )
          }

        } else {
          (FileOpResultMappings(), FilesWrittenAndProblems())
        }
    }

    CompileProblems.report(reporter.value, results._2)

    import Cache._

    val previousMappings = task.previous.getOrElse(Nil)
    val untouchedMappings = previousMappings.toSet -- results._1
    untouchedMappings.filter(_.exists).toSeq ++ results._1
  }

  /**
   * Convenience method to add a source file task into the Asset and TestAsset configurations, along with adding the
   * source file tasks in to their respective collection.
   * @param sourceFileTask The task key to declare.
   * @return The settings produced.
   */
  def addJsSourceFileTasks(sourceFileTask: TaskKey[Seq[File]]): Seq[Setting[_]] = {
    Seq(
      sourceFileTask in Assets := jsSourceFileTask(sourceFileTask, Assets).value,
      sourceFileTask in TestAssets := jsSourceFileTask(sourceFileTask, TestAssets).value,
      sourceFileTask := (sourceFileTask in Assets).value,

      resourceGenerators in Assets <+= (sourceFileTask in Assets),
      resourceGenerators in TestAssets <+= (sourceFileTask in TestAssets)
    )
  }

  /**
   * Execute some arbitrary JavaScript.
   *
   * This method is intended to assist in building SBT tasks that execute generic JavaScript.  For example:
   *
   * {{{
   * myTask := {
   *   executeJs(state.value, engineType.value, Seq((nodeModules in Plugin).value.getCanonicalPath,
   *     baseDirectory.value / "path" / "to" / "myscript.js", Seq("arg1", "arg2"), 30.seconds)
   * }
   * }}}
   *
   * @param state The SBT state.
   * @param engineType The type of engine to use.
   * @param nodeModules The node modules to provide (if the JavaScript engine in use supports this).
   * @param shellSource The script to execute.
   * @param args The arguments to pass to the script.
   * @param timeout The maximum amount of time to wait for the script to finish.
   * @return A JSON status object if one was sent by the script.  A script can send a JSON status object by, as the
   *         last thing it does, sending a DLE character (0x10) followed by some JSON to std out.
   */
  def executeJs(
                 state: State,
                 engineType: EngineType.Value,
                 nodeModules: Seq[String],
                 shellSource: File,
                 args: Seq[String],
                 timeout: FiniteDuration
                 ): Seq[JsValue] = {
    val engineProps = engineTypeToProps(engineType, NodeEngine.nodePathEnv(nodeModules.to[immutable.Seq]))

    withActorRefFactory(state, this.getClass.getName) {
      arf =>
        val engine = arf.actorOf(engineProps)
        implicit val t = Timeout(timeout)
        import ExecutionContext.Implicits.global
        Await.result(
          executeJsOnEngine(engine, shellSource, args, m => state.log.error(m), m => state.log.info(m)),
          timeout
        )
    }
  }

}