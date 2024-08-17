package me.binwang.rss.cmd

import cats.effect.{IO, Resource}
import com.sksamuel.elastic4s.ElasticClient
import com.typesafe.config.{Config, ConfigFactory}
import me.binwang.rss.dao._
import me.binwang.rss.dao.elasticsearch.{ArticleContentElasticDao, ArticleElasticDao, ElasticSearchClient, ArticleSearchElasticDao}
import me.binwang.rss.dao.hybrid.{ArticleContentHybridDao, ArticleHybridDao}
import me.binwang.rss.dao.redis.{ArticleContentHashCheckDao, ArticleHashCheckDao, RedisCommand}
import me.binwang.rss.dao.sql._
import me.binwang.rss.fetch.crawler.{Crawler, HttpCrawler}
import me.binwang.rss.fetch.fetcher.{BackgroundFetcher, FetchUpdater}
import me.binwang.rss.mail.SendGridMailSender
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory
import sttp.client3.SttpBackend
import sttp.client3.http4s.Http4sBackend

class BaseServer(
    val articleDao: ArticleDao,
    val articleContentDao: ArticleContentDao,
    val sourceDao: SourceDao,
    val userDao: UserDao,
    val userSessionDao: UserSessionDao,
    val userDeleteCodeDao: UserDeleteCodeDao,
    val folderDao: FolderDao,
    val folderSourceDao: FolderSourceDao,
    val articleUserMarkingDao: ArticleUserMarkingDao,
    val redditSessionDao: RedditSessionDao,
    val articleSearchDao: ArticleSearchDao,
    val passwordResetDao: PasswordResetDao,
    val paymentCustomerDao: PaymentCustomerDao,
    val moreLikeThisMappingDao: MoreLikeThisMappingDao,
    val articleEmbeddingTaskDao: ArticleEmbeddingTaskDao,
    val importSourcesTaskDao: ImportSourcesTaskDao,
    val mailSender: SendGridMailSender,
    val crawler: Crawler,
    val fetcher: BackgroundFetcher,
    val sttpBackend: SttpBackend[IO, _],
)

object BaseServer {

  implicit val loggerFactory: LoggerFactory[IO] = Slf4jFactory.create[IO]

  def apply(): Resource[IO, BaseServer] = {

    val config: Config = ConfigFactory.load()
    ConnectionPool().flatMap { implicit connectionPool =>
      implicit val elasticClient: ElasticClient = ElasticSearchClient()
      RedisCommand().flatMap { redisClient =>
        val articleSqlDao: ArticleSqlDao = new ArticleSqlDao()
        val articleElasticDao: ArticleElasticDao = new ArticleElasticDao()
        val articleHashDao: ArticleHashCheckDao = new ArticleHashCheckDao(redisClient)
        implicit val articleDao: ArticleDao = new ArticleHybridDao(articleSqlDao, articleElasticDao, Some(articleHashDao))
        val articleDaoWithoutHashCheck = new ArticleHybridDao(articleSqlDao, articleElasticDao, None)

        val articleContentSqlDao: ArticleContentSqlDao = new ArticleContentSqlDao()
        val articleContentElasticDao: ArticleContentElasticDao = new ArticleContentElasticDao()
        val articleContentHashDao: ArticleContentHashCheckDao = new ArticleContentHashCheckDao(redisClient)
        implicit val articleContentDao: ArticleContentDao = new ArticleContentHybridDao(
          articleContentSqlDao, articleContentElasticDao, articleContentHashDao)

        implicit val sourceDao: SourceSqlDao = new SourceSqlDao()
        implicit val userDao: UserSqlDao = new UserSqlDao()
        implicit val userSessionDao: UserSessionSqlDao = new UserSessionSqlDao()
        implicit val userDeleteCodeDao: UserDeleteCodeSqlDao = new UserDeleteCodeSqlDao()
        implicit val folderDao: FolderSqlDao = new FolderSqlDao()
        implicit val folderSourceDao: FolderSourceSqlDao = new FolderSourceSqlDao()
        implicit val articleUserMarkingDao: ArticleUserMarkingSqlDao = new ArticleUserMarkingSqlDao()
        implicit val redditSessionDao: RedditSessionSqlDao = new RedditSessionSqlDao()
        implicit val articleSearchDao: ArticleSearchDao = new ArticleSearchElasticDao(
          config.getDouble("search.search-boost"),
          config.getDouble("search.knn-boost"),
          config.getDouble("search.min-score"),
        )
        implicit val passwordResetDao: PasswordResetDao = new PasswordResetSqlDao()
        implicit val paymentCustomerDao: PaymentCustomerSqlDao = new PaymentCustomerSqlDao()
        implicit val moreLikeThisMappingDao: MoreLikeThisMappingSqlDao = new MoreLikeThisMappingSqlDao()
        implicit val articleEmbeddingTaskDao: ArticleEmbeddingTaskSqlDao = new ArticleEmbeddingTaskSqlDao()
        implicit val importSourcesTaskDao: ImportSourcesTaskSqlDao = new ImportSourcesTaskSqlDao()

        val mailSender = new SendGridMailSender()

        Http4sBackend.usingDefaultEmberClientBuilder[IO]().evalMap { sttpBackend =>
          val crawler = new HttpCrawler(sttpBackend)
          val updater = new FetchUpdater(sourceDao, articleDaoWithoutHashCheck, articleContentDao, articleHashDao,
            articleEmbeddingTaskDao)
          articleDao.createTable() >>
            articleContentDao.createTable() >>
            sourceDao.createTable() >>
            userDao.createTable() >>
            userSessionDao.createTable() >>
            userDeleteCodeDao.createTable() >>
            folderDao.createTable() >>
            folderSourceDao.createTable() >>
            articleUserMarkingDao.createTable() >>
            redditSessionDao.createTable() >>
            passwordResetDao.createTable() >>
            paymentCustomerDao.createTable() >>
            moreLikeThisMappingDao.createTable() >>
            articleEmbeddingTaskDao.createTable() >>
            importSourcesTaskDao.createTable() >>
            BackgroundFetcher(crawler, sourceDao, updater, config.getInt("fetcher.batchSize")).map { fetcher =>
              new BaseServer(
                articleDao,
                articleContentDao,
                sourceDao,
                userDao,
                userSessionDao,
                userDeleteCodeDao,
                folderDao,
                folderSourceDao,
                articleUserMarkingDao,
                redditSessionDao,
                articleSearchDao,
                passwordResetDao,
                paymentCustomerDao,
                moreLikeThisMappingDao,
                articleEmbeddingTaskDao,
                importSourcesTaskDao,
                mailSender,
                crawler,
                fetcher,
                sttpBackend,
              )
            }
        }
      }
    }
  }

}
