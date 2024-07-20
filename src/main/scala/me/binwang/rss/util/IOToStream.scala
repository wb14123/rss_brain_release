package me.binwang.rss.util

import cats.effect.IO

object IOToStream {
  def seqToStream[T](list: IO[Seq[T]]): fs2.Stream[IO, T] = {
    fs2.Stream.eval(list).flatMap(x => fs2.Stream.apply(x: _*))
  }
}
