package com.codacy.rules

import java.io.File
import java.nio.file.Files

import com.codacy.api.CoverageReport
import com.codacy.api.client.{FailedResponse, SuccessfulResponse}
import com.codacy.api.helpers.FileHelper
import com.codacy.api.service.CoverageServices
import com.codacy.model.configuration.{
  ApiTokenAuthenticationConfig,
  BaseConfig,
  FinalConfig,
  ProjectTokenAuthenticationConfig,
  ReportConfig
}
import com.codacy.parsers.CoverageParser
import com.codacy.transformation.PathPrefixer
import com.codacy.plugins.api.languages.Languages
import com.codacy.rules.commituuid.CommitUUIDProvider
import wvlet.log.LogSupport

import scala.collection.JavaConverters._

class ReportRules(coverageServices: => CoverageServices) extends LogSupport {

  private val rootProjectDir = new File(System.getProperty("user.dir"))
  private val rootProjectDirIterator = Files
    .walk(rootProjectDir.toPath)
    .iterator()
    .asScala
    .map(_.toFile)

  private def flatten[T](v: Either[String, Seq[Either[String, T]]]): Seq[Either[String, T]] = {
    v.left.map(l => Seq(Left(l))).merge
  }

  private def sendFilesReportForCommit(
      files: List[File],
      config: ReportConfig,
      partial: Boolean,
      commitUUID: String
  ): Either[String, String] = {
    val finalConfig = config.copy(partial = partial)
    files
      .flatMap { file =>
        logger.info(s"Parsing coverage data from: ${file.getAbsolutePath} ...")
        val res: Either[String, Seq[Either[String, Unit]]] = for {
          _ <- validateFileAccess(file)
          report <- CoverageParser.parse(rootProjectDir, file, forceParser = config.forceCoverageParser).map {
            coverageResult =>
              logger.info(s"Coverage parser used is ${coverageResult.parser}")
              transform(coverageResult.report)(finalConfig)
          }
          _ <- storeReport(report, file)
        } yield
          finalConfig.languageOpt match {
            case Some(language) =>
              Seq(sendReport(report, language, finalConfig, commitUUID, file))
            case None =>
              flatten(
                splitReportByLanguages(report)
                  .map(_.map {
                    case (language, report) =>
                      sendReport(report, language, finalConfig, commitUUID, file)
                  })
              )
          }
        flatten(res)
      }
      .collectFirst {
        case Left(l) => Left(l)
      }
      .getOrElse(Right("All coverage data uploaded."))
  }

  def codacyCoverage(config: ReportConfig): Either[String, String] = {
    withCommitUUID(config.baseConfig) { commitUUID =>
      logAuthenticationToken(config)

      val filesEither = guessReportFiles(config.coverageReports, rootProjectDirIterator)

      val operationResult = filesEither.flatMap { files =>
        if (files.length > 1 && !config.partial) {
          logger.info("More than one file. Considering a partial coverage report")
          for {
            _ <- sendFilesReportForCommit(files, config, partial = true, commitUUID)
            f <- finalReport(FinalConfig(config.baseConfig))
          } yield f
        } else {
          sendFilesReportForCommit(files, config, partial = config.partial, commitUUID)
        }
      }
      if (config.partial) {
        logger.info(
          """To complete the reporting process, call coverage-reporter with the final flag.
          | Check https://docs.codacy.com/coverage-reporter/adding-coverage-to-your-repository/#multiple-reports
          | for more information.""".stripMargin
        )
      }
      operationResult
    }
  }

  private def logAuthenticationToken(config: ReportConfig): Unit = {
    config.baseConfig.authentication match {
      case ProjectTokenAuthenticationConfig(projectToken) => logger.debug(s"Project token: $projectToken")
      case ApiTokenAuthenticationConfig(apiToken, _, _) => logger.debug(s"API token: $apiToken")
    }
  }

  private[rules] def validateFileAccess(file: File) = {
    file match {
      case file if !file.exists =>
        Left(s"File ${file.getAbsolutePath} does not exist.")
      case file if !file.canRead =>
        Left(s"Missing read permissions for coverage report file: ${file.getAbsolutePath}")
      case _ =>
        Right(())
    }
  }

  /**
    * Store Report
    *
    * Store the parsed report for troubleshooting purposes
    * @param report coverage report to be stored
    * @param file report file
    * @return either an error message or nothing
    */
  private[rules] def storeReport(report: CoverageReport, file: File) = {
    if (report.fileReports.isEmpty)
      Left(s"The provided coverage report ${file.getAbsolutePath} generated an empty result.")
    else {
      val codacyReportFile = File.createTempFile("codacy-coverage-", ".json")

      logger.debug(s"Saving parsed coverage report to ${codacyReportFile.getAbsolutePath}")
      logger.debug(report.toString)
      FileHelper.writeJsonToFile(codacyReportFile, report)

      logUploadedFileInfo(codacyReportFile)
      Right(codacyReportFile.getAbsolutePath)
    }
  }

  /**
    * Send Report
    *
    * Send the parsed report to coverage services with the given language and commitUUID
    * @param report coverage report to be sent
    * @param language language detected in files or specified by user input
    * @param config configuration
    * @param commitUUID unique id of commit being reported
    * @param file report file
    * @return either an error message or nothing
    */
  private def sendReport(
      report: CoverageReport,
      language: String,
      config: ReportConfig,
      commitUUID: String,
      file: File
  ): Either[String, Unit] = {
    val coverageResponse = config.baseConfig.authentication match {
      case _: ProjectTokenAuthenticationConfig =>
        coverageServices.sendReport(commitUUID, language, report, config.partial)

      case ApiTokenAuthenticationConfig(_, username, projectName) =>
        coverageServices.sendReportWithProjectName(username, projectName, commitUUID, language, report, config.partial)
    }
    coverageResponse match {
      case SuccessfulResponse(value) =>
        logger.info(s"Coverage data uploaded. ${value.success}")
        Right(())
      case failed: FailedResponse =>
        val message = handleFailedResponse(failed)
        Left(s"Failed to upload coverage report ${file.getAbsolutePath}: $message")
    }
  }

  /**
    * Split report by languages
    *
    * This function splits a report into multiple reports with the same language
    * guessed by the file extension.
    * We skip files that have extensions not recognised by Languages.forPath
    * We generate multiple coverage reports that share the same total and have
    * split FileCoverageReports since we can't know to split total between multiple
    * reports so we assume they get the same score.
    * @param report coverage report
    * @return Either a Map from the languages to the partial coverage report or an error
    *         message when there are no fileReports
    */
  private[rules] def splitReportByLanguages(report: CoverageReport): Either[String, Seq[(String, CoverageReport)]] = {
    if (report.fileReports.isEmpty) Left("Can't guess the language due to empty coverage report")
    else {
      Right(
        report.fileReports
          .groupBy(r => Languages.forPath(r.filename).map(_.name))
          .collect {
            case (Some(language), fileReports) => language -> CoverageReport(report.total, fileReports)
          }(collection.breakOut)
      )
    }
  }

  /**
    * Guess the report file
    *
    * This function try to guess the report language based on common report file names.
    * @param files coverage file option provided by the config
    * @param pathIterator path iterator to search the files
    * @return the guessed report files on the right or an error on the left.
    */
  private[rules] def guessReportFiles(files: List[File], pathIterator: Iterator[File]): Either[String, List[File]] = {
    val JacocoRegex = """(jacoco.*\.xml)""".r
    val CoberturaRegex = """(cobertura\.xml)""".r
    val LCOVRegex = """(lcov(.info|.dat)|.*\.lcov)""".r
    val CloverRegex = """(clover\.xml)""".r
    val DotcoverRegex = """(dotcover\.xml)""".r
    val OpencoverRegex = """(opencover\.xml)""".r
    val PhpUnitRegex = """(index\.xml)""".r

    val phpUnitCoverageFolder = "coverage-xml"

    files match {
      case value if value.isEmpty =>
        val foundFiles = pathIterator
          .filter(
            file =>
              file.getName match {
                case JacocoRegex(_) | CoberturaRegex(_) | LCOVRegex(_) | CloverRegex(_) | DotcoverRegex(_) |
                    OpencoverRegex(_) =>
                  true
                // index.xml is a common name, so we just consider it if it's inside the coverage-xml folder
                case PhpUnitRegex(_) => file.getParent == phpUnitCoverageFolder
                case _ => false
            }
          )
          .toList
        if (foundFiles.isEmpty)
          Left("Can't detect any coverage report automatically. Specify the report with the flag -r")
        else
          Right(foundFiles)
      case value =>
        Right(value)
    }
  }

  /**
    * Log uploaded file information
    *
    * This function log some summary information about the uploaded coverage file.
    * @param codacyReportFile coverage report file
    */
  private def logUploadedFileInfo(codacyReportFile: File): Unit = {
    // Convert to kB with 2 decimal places
    val fileSize = ((codacyReportFile.length / 1024.0) * 100).toInt / 100.0
    val filePath = codacyReportFile.getAbsolutePath

    logger.info(s"Generated coverage report: $filePath ($fileSize kB)")
    logger.info("Uploading coverage data...")
  }

  /**
    * Handle failed response
    *
    * This function handle failed response and transform it to a user readable message.
    * @param response failed response
    * @return user readable message
    */
  private[rules] def handleFailedResponse(response: FailedResponse): String = {
    if (response.message.contains("not found")) {
      "Request URL not found. Check if the project API Token you are using and the API base URL are valid."
    } else {
      response.message
    }
  }

  private def transform(report: CoverageReport)(config: ReportConfig): CoverageReport = {
    val transformations = Set(new PathPrefixer(config.prefix))
    transformations.foldLeft(report) { (report, transformation) =>
      transformation.execute(report)
    }
  }

  def finalReport(config: FinalConfig): Either[String, String] = {
    withCommitUUID(config.baseConfig) { commitUUID =>
      coverageServices.sendFinalNotification(commitUUID) match {
        case SuccessfulResponse(value) =>
          Right(s"Final coverage notification sent. ${value.success}")
        case FailedResponse(message) =>
          Left(s"Failed to send final coverage notification: $message")
      }
    }
  }

  private def withCommitUUID[T](config: BaseConfig)(block: String => Either[String, T]): Either[String, T] = {
    val maybeCommitUUID = config.commitUUID.map(Right(_)).getOrElse {
      val envVars = sys.env.filter { case (_, value) => value.trim.nonEmpty }
      CommitUUIDProvider.getFromAll(envVars)
    }

    maybeCommitUUID.flatMap(uuid => block(uuid.value))
  }

}
