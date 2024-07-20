package me.binwang.rss.cmd

import cats.effect.{IO, Resource}
import cats.implicits._
import me.binwang.rss.llm.OpenAILLM
import me.binwang.rss.service._
import me.binwang.rss.sourcefinder.{HtmlSourceFinder, MultiSourceFinder, RegexSourceFinder}
import me.binwang.rss.util.Throttler
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

case class Services (
    articleService: ArticleService,
    folderService: FolderService,
    sourceService: SourceService,
    userService: UserService,
    moreLikeThisService: MoreLikeThisService,
    stripePaymentService: StripePaymentService,
    applePaymentService: ApplePaymentService,
    systemService: SystemService,
)

object Services {

  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]
  private val throttler: Throttler = Throttler()

  def apply(baseServer: BaseServer): Resource[IO, Services] = {

    val authorizer: Authorizer = new Authorizer(throttler, baseServer.userSessionDao, baseServer.folderDao)
    val llm = new OpenAILLM(baseServer.sttpBackend)

    Resource.eval(Seq(
      RegexSourceFinder("rssbrain-regex-rules.json"),
      RegexSourceFinder("rsshub-regex-rules.json"),
      IO.pure(new HtmlSourceFinder()(baseServer.crawler)),
    ).sequence.map(new MultiSourceFinder(_))).map { sourceFinder =>

      new Services(
        new ArticleService(baseServer.articleDao, baseServer.articleContentDao, baseServer.articleUserMarkingDao,
          baseServer.articleSearchDao, llm, authorizer),
        new FolderService(baseServer.folderDao, baseServer.folderSourceDao,
          baseServer.sourceDao, authorizer),
        new SourceService(baseServer.sourceDao, baseServer.folderSourceDao, baseServer.folderDao, baseServer.fetcher,
          authorizer, sourceFinder),
        new UserService(baseServer.userDao, baseServer.userSessionDao, baseServer.userDeleteCodeDao,
          baseServer.articleUserMarkingDao, baseServer.folderSourceDao, baseServer.moreLikeThisMappingDao,
          baseServer.folderDao, baseServer.redditSessionDao, baseServer.passwordResetDao, baseServer.paymentCustomerDao,
          baseServer.mailSender, authorizer),
        new MoreLikeThisService(baseServer.moreLikeThisMappingDao, authorizer),
        new StripePaymentService(baseServer.userDao, baseServer.userSessionDao,
          baseServer.paymentCustomerDao, baseServer.sourceDao, authorizer, baseServer.mailSender),
        new ApplePaymentService(baseServer.userDao, baseServer.userSessionDao,
          baseServer.paymentCustomerDao, baseServer.sourceDao, authorizer, baseServer.mailSender),
        new SystemService(),
      )
    }

  }
}
