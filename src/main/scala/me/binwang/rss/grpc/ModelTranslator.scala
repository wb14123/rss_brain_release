package me.binwang.rss.grpc

import cats.effect.{IO, Resource}
import me.binwang.scala2grpc.GrpcTypeTranslator

import java.io.{ByteArrayInputStream, InputStream}
import java.time.{Instant, ZoneId, ZonedDateTime}
import scala.language.implicitConversions

object ModelTranslator extends GrpcTypeTranslator {

  implicit def longToDateTime(timestamp: Long): ZonedDateTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
  implicit def dateTimeToLong(datetime: ZonedDateTime): Long = datetime.toInstant.toEpochMilli
  implicit def optionLongToDateTime(timestamp: Option[Long]): Option[ZonedDateTime] = timestamp.map(longToDateTime)
  implicit def optionDateTimeToLong(datetime: Option[ZonedDateTime]): Option[Long] = datetime.map(dateTimeToLong)
  implicit def inputStreamToString(input: String): Resource[IO, InputStream] = {
    Resource.make {
      IO(new ByteArrayInputStream(input.getBytes("UTF-8")))
    } { i =>
      IO(i.close())
    }
  }

}
