package me.binwang.rss.cmd

import cats.effect.unsafe.IORuntime
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import sttp.client3._

import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Future}

class GRPCAndHttpServerSpec extends AnyFunSpec with BeforeAndAfterEach with Matchers{

  implicit val ioRuntime: IORuntime = IORuntime.global

  describe("GRPCServer") {

    ignore("should start GRPC server") {

      val executor = Executors.newFixedThreadPool(2)
      val mainThread = ExecutionContext.fromExecutor(executor)
      val backend = HttpURLConnectionBackend()

      Future(GRPCAndHttpServer.main(Array()))(mainThread)
      Thread.sleep(3000)

      val config= ConfigFactory.load()
      val grpcWebUrl = s"http://${config.getString("grpc.ip")}:${config.getInt("grpc.port")}"

      basicRequest.get(uri"$grpcWebUrl").response(asString).send(backend).statusText shouldBe "Not Found"

      executor.shutdownNow()
    }

  }
}
