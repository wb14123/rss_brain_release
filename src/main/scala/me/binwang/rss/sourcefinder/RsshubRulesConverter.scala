package me.binwang.rss.sourcefinder

import cats.effect.{ExitCode, IO, IOApp}
import io.circe.parser
import io.circe.syntax._
import me.binwang.rss.sourcefinder.RegexSourceFinder.{RegexMatchRuleJson, RegexMatchRulesJson}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.io.PrintWriter

object RsshubRulesConverter extends IOApp {

  private implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]
  private val logger = LoggerFactory.getLoggerFromClass[IO](this.getClass)

  val RADAR_RULES_JSON_FILE = "radar-rules.json"

  // need to be the same as defined in build.sbt
  val OUTPUT_FILE = "rsshub-regex-rules.json"

  private val paramPattern = ":(\\w+)".r

  override def run(args: List[String]): IO[ExitCode] = {
    val to = args.head
    args(1) match {
      case "generate" =>
        logger.info(s"Convert from $RADAR_RULES_JSON_FILE to $to").map { _ =>
          val radarJsonStr = scala.io.Source.fromResource(RADAR_RULES_JSON_FILE).mkString
          val rules = RegexMatchRulesJson(parseJson(radarJsonStr))
          val writer = new PrintWriter(to)
          writer.print(rules.asJson)
          writer.close()
        }.map(_ => ExitCode.Success)
      case "validate" =>
        RegexSourceFinder(OUTPUT_FILE).map(_ => ExitCode.Success)
      case _ =>
        logger.error("Second arg error. Valid options: <generate> or <validate>").map{_ => ExitCode.Error}
    }
  }

  def parseJson(jsonStr: String): Seq[RegexMatchRuleJson] = {
    parser.parse(jsonStr) match {
      case Left(err) => throw err
      case Right(json) =>
        json.asObject.get.toMap.flatMap { case (rootDomain, value) =>
          value.asObject.get.toMap
            .filter(e => !e._1.equals("_name"))
            .flatMap { case (subDomain, rules) =>
              val domain = if (subDomain.equals(".")) {
                rootDomain
              } else {
                subDomain + "." + rootDomain
              }
              rules.asArray.get
                .map(_.asObject.get.toMap)
                .filter(_.get("target").map(_.asString).isDefined)
                .filter { rule =>
                  val sourceOpt = rule.get("source")
                  if (sourceOpt.isEmpty) {
                    logger.warn(s"Rule doesn't have source field: $rule")
                  }
                  sourceOpt.isDefined
                }
                .flatMap { rule =>
                  rule("source").asArray.getOrElse(Seq(rule("source"))).map { s =>
                    val rsshubSource = s.asString.get
                    val rsshubTarget = rule("target").asString.get
                    // replace from longest string so it will not partially replace another param
                    val params = paramPattern
                      .findAllMatchIn(rsshubSource)
                      .map(_.group(1))
                      .toSeq
                      .sortBy(0 - _.length)
                    params.foreach(p => if (p.equals("any") || p.equals("proto"))
                      throw new Exception("Param cannot be name as \"any\" or \"proto\""))
                    val source = params.fold(rsshubSource) { (oldSource, param) =>
                      oldSource.replaceAll(":" + param, s"(?<${cleanParam(param)}>[\\\\w|_]+)")
                    }
                    val finalSource = if (source.equals("/")) "/?" else source
                    val target = params.fold(rsshubTarget) { (oldTarget, param) =>
                      oldTarget
                        .replaceAll("\\:" + param + "\\?", "\\$" + cleanParam(param))
                        .replaceAll("\\:" + param, "\\$" + cleanParam(param))
                    }.replaceAll("/:(\\w+)\\?", "")
                    if (paramPattern.matches(target)) {
                      logger.warn(s"Params in source and target not match, rule: $rule")
                    }
                    RegexMatchRuleJson(
                      source = "https?://" + domain + "(?<any>.*)" + finalSource,
                      target = "https://rsshub.app" + target + "?format=atom&mode=fulltext",
                      recommend = None,
                      recommendReason = None,
                    )
                  }
                }
            }
        }.toSeq
    }
  }

  // clean up param name to allow it in regex
  private def cleanParam(param: String): String = param.replaceAll("_", "")

}
