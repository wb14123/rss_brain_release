package me.binwang.rss.dao.elasticsearch

import cats.effect.unsafe.IORuntime
import com.sksamuel.elastic4s.ElasticClient
import me.binwang.rss.generator.{ArticleContents, Articles}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class ArticleContentElasticDaoSpec extends AnyFunSpec with BeforeAndAfterEach with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global

  private implicit val elasticClient: ElasticClient = ElasticSearchClient()
  private val articleDao = new ArticleElasticDao()
  private val articleContentDao = new ArticleContentElasticDao()

  override def beforeEach(): Unit = {
    articleDao.dropTable().unsafeRunSync()
    articleDao.createTable().unsafeRunSync()
    articleContentDao.dropTable().unsafeRunSync()
    articleContentDao.createTable().unsafeRunSync()
  }

  describe("ArticleContent Elastic DAO") {

    it("should insert and get article content") {
      val articleContent = ArticleContents.get()
      articleContentDao.insertOrUpdate(articleContent).unsafeRunSync() shouldBe true
      articleContentDao.get(articleContent.id).unsafeRunSync().get shouldBe articleContent
    }

    it("should get none if no content found") {
      articleContentDao.get("testid").unsafeRunSync() shouldBe None
    }

    it("should upsert if exists") {
      val c1 = ArticleContents.get()
      val c2 = ArticleContents.get(Some(c1.id))
      c1.id shouldBe c2.id
      articleContentDao.insertOrUpdate(c1).unsafeRunSync()
      articleContentDao.insertOrUpdate(c2).unsafeRunSync()
      articleContentDao.get(c1.id).unsafeRunSync().get shouldBe c2
    }

    it("should insert article if content exists") {
      val article = Articles.get()
      val articleContent = ArticleContents.get()

      articleContentDao.insertOrUpdate(articleContent).unsafeRunSync() shouldBe true
      articleDao.insertOrUpdate(article).unsafeRunSync()

      articleDao.get(article.id).unsafeRunSync().get.description shouldBe article.description
      articleContentDao.get(articleContent.id).unsafeRunSync().get shouldBe articleContent
    }

    it("should insert content if article exists") {
      val article = Articles.get()
      val articleContent = ArticleContents.get()

      articleDao.insertOrUpdate(article).unsafeRunSync()
      articleContentDao.insertOrUpdate(articleContent).unsafeRunSync() shouldBe true

      articleDao.get(article.id).unsafeRunSync().get.description shouldBe article.description
      articleContentDao.get(articleContent.id).unsafeRunSync().get shouldBe articleContent
    }
  }

}
