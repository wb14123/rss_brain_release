package me.binwang.rss.cmd

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.implicits._
import com.typesafe.config.{Config, ConfigFactory}
import fs2.grpc.syntax.all.fs2GrpcSyntaxServerBuilder
import io.grpc
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import me.binwang.rss.grpc.generator.GenerateGRPC
import me.binwang.rss.metric.MetricServer
import me.binwang.rss.reddit.RedditGateway
import me.binwang.rss.web.{AppleWebService, ExportWebService, RedditService, StripeWebService}
import me.binwang.rss.webview.basic.ErrorHandler
import me.binwang.rss.webview.routes._
import org.http4s.server.staticcontent.resourceServiceBuilder
import org.http4s.server.{Router, Server}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.net.{InetAddress, InetSocketAddress}

object GRPCAndHttpServer extends IOApp {

  private val config: Config = ConfigFactory.load()
  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]
  private val logger = LoggerFactory.getLoggerFromClass[IO](this.getClass)

  private def httpServer(baseServer: BaseServer, services: Services): Resource[IO, Server] = {
    val httpRoute = Router("/" -> (new AppleWebService(services.applePaymentService).route <+>
      new ExportWebService(services.folderService).route <+>
      new RedditService(baseServer.redditSessionDao,
        new RedditGateway()(Some(baseServer.redditSessionDao), baseServer.sttpBackend)).route <+>
      new StripeWebService(services.stripePaymentService).route
      ))
    HttpServer(httpRoute, config.getString("http.ip"), config.getInt("http.port"), logBody = true)
  }

  private def frontendServer(services: Services): Resource[IO, Server] = {
    val assetsRoutes = resourceServiceBuilder[IO]("/webview").toRoutes
    val httpRoute = ErrorHandler(Router("/" -> (assetsRoutes <+>
      new LoginView(services.userService).routes <+>
      new RootView(services.userService).routes <+>
      new FolderListView(services.folderService, services.sourceService, services.userService).routes <+>
      new SourceView(services.sourceService, services.userService).routes <+>
      new ArticleListView(services.articleService, services.userService, services.folderService,
        services.sourceService).routes <+>
      new ArticleView(services.articleService).routes <+>
      new UserView(services.userService).routes <+>
      new PaymentView(services.userService, services.stripePaymentService).routes <+>
      new ImportFeedView(services.sourceService, services.folderService).routes <+>
      new RecommendationView(services.moreLikeThisService, services.articleService, services.sourceService,
        services.folderService).routes
    )))
    HttpServer(httpRoute, config.getString("frontend.ip"), config.getInt("frontend.port"), logBody = false)
  }

  private def grpcServer(services: Services): Resource[IO, grpc.Server] = {
    val serverBuilder = NettyServerBuilder.forAddress(new InetSocketAddress(
      InetAddress.getByName(config.getString("grpc.ip")),
      config.getInt("grpc.port")
    ))
    GenerateGRPC.addServicesToServerBuilder(serverBuilder, Seq(
      services.articleService,
      services.folderService,
      services.sourceService,
      services.userService,
      services.moreLikeThisService,
      services.stripePaymentService,
      services.applePaymentService,
      services.systemService,
    )).flatMap { sb =>
      fs2GrpcSyntaxServerBuilder(sb)
        .resource[IO]
        .evalMap(server => IO(server.start()))
    }
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val argsSet = args.toSet
    (for {
      baseServer <- BaseServer()
      services <- Services(baseServer)
      _ <- MetricServer()
      _ <- Resource.eval(logger.info("Started metric"))
      _ <- if (argsSet.contains("--grpc"))
        grpcServer(services).evalMap(_ => logger.info("Started grpc server")) else Resource.unit[IO]
      _ <- if (argsSet.contains("--http"))
        httpServer(baseServer, services).evalMap(_ => logger.info("Started http API server")) else Resource.unit[IO]
      _ <- if (argsSet.contains("--frontend"))
        frontendServer(services).evalMap(_ => logger.info("Started web server")) else Resource.unit[IO]
    } yield ()).useForever
  }

}
