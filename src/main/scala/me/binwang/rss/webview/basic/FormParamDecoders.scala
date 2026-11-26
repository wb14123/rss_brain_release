package me.binwang.rss.webview.basic

import io.circe.Decoder

/**
 * htmx sends all values of a json-enc request as strings, including booleans and numbers.
 */
trait FormParamDecoders {

  implicit val booleanFormDecoder: Decoder[Boolean] = Decoder.decodeBoolean.or(
    Decoder.decodeString.emap {
      case "true" => Right(true)
      case "false" => Right(false)
      case value => Left(s"Expected boolean, got $value")
    })

  implicit val longFormDecoder: Decoder[Long] = Decoder.decodeLong.or(
    Decoder.decodeString.emap(value => value.toLongOption.toRight(s"Expected long, got $value")))

}
