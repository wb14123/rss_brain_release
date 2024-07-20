package me.binwang.rss.webview.basic

import _root_.scalatags.generic.Frag
import org.http4s.Charset.`UTF-8`
import org.http4s.{Charset, EntityEncoder, MediaType}
import org.http4s.headers.`Content-Type`

trait ScalatagsSeqInstances {
  implicit def scalatagsSeqEncoder[F[_], C <: Seq[Frag[_, String]]]
      (implicit charset: Charset = `UTF-8`): EntityEncoder[F, C] =
    contentSeqEncoder(MediaType.text.html)

  private def contentSeqEncoder[F[_], C <: Seq[Frag[_, String]]](mediaType: MediaType)
      (implicit charset: Charset): EntityEncoder[F, C] =
    EntityEncoder
      .stringEncoder[F]
      .contramap[C](_.map(_.render).mkString)
      .withContentType(`Content-Type`(mediaType, charset))
}

