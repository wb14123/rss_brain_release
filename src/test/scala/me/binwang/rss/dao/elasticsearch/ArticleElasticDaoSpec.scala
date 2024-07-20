package me.binwang.rss.dao.elasticsearch

import cats.effect.unsafe.IORuntime
import com.sksamuel.elastic4s.ElasticClient
import me.binwang.rss.generator.Articles
import me.binwang.rss.model.{MediaContent, MediaGroup, MediaGroups}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class ArticleElasticDaoSpec extends AnyFunSpec with BeforeAndAfterEach with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global
  private implicit val elasticClient: ElasticClient = ElasticSearchClient()
  private val articleDao = new ArticleElasticDao()

  override def beforeEach(): Unit = {
    articleDao.dropTable().unsafeRunSync()
    articleDao.createTable().unsafeRunSync()
  }

  describe("Article Elastic DAO") {

    it("should insert and get data") {
      val article = Articles.get()
      articleDao.insertOrUpdate(article).unsafeRunSync()
      articleDao.get(article.id).unsafeRunSync().get.description shouldBe article.description
    }

    it("should insert and get article with media groups") {
      val article = Articles.get().copy(mediaGroups = Some(MediaGroups(Seq(
        MediaGroup(content = MediaContent("https://abc1.com")),
        MediaGroup(content = MediaContent("https://abc2.com")),
      ))))
      articleDao.insertOrUpdate(article).unsafeRunSync() shouldBe true
      articleDao.get(article.id).unsafeRunSync().get shouldBe article
    }

    it("should get none if not found") {
      articleDao.get("id").unsafeRunSync() shouldBe None
    }

    it("should upsert if exist") {
      // the implementation of ArticleElasticDao.insertIfNotExist will actually upsert the doc so that
      val article1 = Articles.get()
      val article2 = Articles.get(Some(article1.sourceID), Some(article1.guid))
      article1.id shouldBe article2.id
      articleDao.insertOrUpdate(article1).unsafeRunSync()
      articleDao.insertOrUpdate(article2).unsafeRunSync()
      articleDao.get(article1.id).unsafeRunSync().get.description != article1.description shouldBe true
      articleDao.get(article1.id).unsafeRunSync().get.description shouldBe article2.description
    }
  }
}
