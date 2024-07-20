package me.binwang.rss.generator

import cats.effect.IO
import cats.effect.kernel.Resource
import fs2.grpc.syntax.all._
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.{Metadata, Server}
import me.binwang.aitext.ai_text
import me.binwang.aitext.ai_text.{GetEmbeddingRequest, SentenceTransformerFs2Grpc}

class MockSentenceTransformServer(port: Int, mockGetEmbedding: String => Seq[Double]) {

  class SentenceTransformerService extends SentenceTransformerFs2Grpc[IO, Metadata] {
    override def getEmbedding(request: GetEmbeddingRequest, ctx: Metadata): IO[ai_text.Vector] = {
      IO.pure(new ai_text.Vector(mockGetEmbedding(request.text)))
    }
  }

  def run(): Resource[IO, Server] = {
    SentenceTransformerFs2Grpc.bindServiceResource(new SentenceTransformerService()).flatMap { service =>
      NettyServerBuilder
        .forPort(port)
        .addService(service)
        .resource[IO]
        .evalMap(server => IO(server.start()))
    }
  }

}
