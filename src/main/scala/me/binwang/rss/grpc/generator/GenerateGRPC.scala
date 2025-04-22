package me.binwang.rss.grpc.generator

import cats.effect.IO
import io.grpc.{Metadata, Status, StatusRuntimeException}
import me.binwang.rss.grpc.ModelTranslator
import me.binwang.rss.model.ArticleListLayout.ArticleListLayout
import me.binwang.rss.model.ArticleOrder.ArticleOrder
import me.binwang.rss.model.FetchStatus.FetchStatus
import me.binwang.rss.model.ID.ID
import me.binwang.rss.model.LLMEngine.LLMEngine
import me.binwang.rss.model.MoreLikeThisType.MoreLikeThisType
import me.binwang.rss.model.NSFWSetting.NSFWSetting
import me.binwang.rss.model._
import me.binwang.rss.service._
import me.binwang.rss.sourcefinder.SourceResult
import me.binwang.scala2grpc.{ChainedGrpcHook, ErrorWrapperHook, GRPCGenerator, GrpcHook, RequestLoggerHook}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import java.io.InputStream
import java.time.ZonedDateTime
import scala.reflect.runtime.universe.{Type, typeOf}

object GenerateGRPC extends GRPCGenerator {
  override val protoJavaPackage: String = "me.binwang.rss.grpc"
  override val protoPackage: String = "rss"

  override val customTypeMap: Map[String, Type] = Map(
    typeOf[ZonedDateTime].toString -> typeOf[Long],
    typeOf[ID].toString -> typeOf[String],
    typeOf[InputStream].toString -> typeOf[String],
  )

  override val implicitTransformClass: Option[Class[_]] = Some(ModelTranslator.getClass)

  override val modelClasses: Seq[Type] = Seq(
    typeOf[Article],
    typeOf[ArticleIDs],
    typeOf[ArticleContent],
    typeOf[ArticleOrder],
    typeOf[ArticleListLayout],
    typeOf[Folder],
    typeOf[FolderCreator],
    typeOf[FolderUpdater],
    typeOf[FolderSource],
    typeOf[FolderSourceMapping],
    typeOf[FolderSourceMappingUpdater],
    typeOf[FullArticle],
    typeOf[FetchStatus],
    typeOf[Source],
    typeOf[SourceUpdater],
    typeOf[NSFWSetting],
    typeOf[SearchEngine],
    typeOf[User],
    typeOf[UserInfo],
    typeOf[UserUpdater],
    typeOf[UserSession],
    typeOf[ArticleUserMarking],
    typeOf[ArticleWithUserMarking],
    typeOf[FullArticleWithUserMarking],
    typeOf[RedditSession],
    typeOf[SourceResult],
    typeOf[SearchOptions],
    typeOf[MediaGroups],
    typeOf[MediaGroup],
    typeOf[MediaContent],
    typeOf[MediaRating],
    typeOf[MediaThumbnail],
    typeOf[MediaPlayer],
    typeOf[MediaText],
    typeOf[PaymentCustomer],
    typeOf[MoreLikeThisMapping],
    typeOf[MoreLikeThisType],
    typeOf[TermWeight],
    typeOf[TermWeights],
    typeOf[SearchTerms],
    typeOf[ImportSourcesTask],
    typeOf[ImportFailedSource],
    typeOf[LLMEngine],
  )

  override val serviceClasses: Seq[Type] = Seq(
    typeOf[ArticleService],
    typeOf[FolderService],
    typeOf[SourceService],
    typeOf[UserService],
    typeOf[StripePaymentService],
    typeOf[ApplePaymentService],
    typeOf[SystemService],
    typeOf[MoreLikeThisService],
  )

  override implicit def loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]
  private val logger = LoggerFactory.getLoggerFromClass(this.getClass)

  private val errorWrapperHook = new ErrorWrapperHook() {
    override protected def handleAttempt[T](metadata: Metadata, msg: String)(result: Either[Throwable, T]): IO[Either[Throwable, T]] = {
      result match {
        case Left(error) =>
          (error match {
            case _: UserNotAuthorized =>
              logger.info(s"User unauthorized: ${error.getMessage}") >>
                IO.pure(Status.UNAUTHENTICATED.withCause(error).withDescription(error.toString))
            case _: UserSubscriptionEnded =>
              logger.info(s"User subscription ended: ${error.getMessage}") >>
                IO.pure(Status.UNAUTHENTICATED.withCause(error).withDescription(error.toString))
            case _ =>
              logger.error(error)("Error while handling request") >>
                IO.pure(Status.UNKNOWN.withCause(error).withDescription(error.toString))
          }).map { status =>
            Left(new StatusRuntimeException(status, metadata))
          }
        case value => IO.pure(value)
      }
    }
  }

  override implicit def grpcHook: GrpcHook = new ChainedGrpcHook(Seq(
    new RequestLoggerHook(),
    errorWrapperHook,
  ))

}
