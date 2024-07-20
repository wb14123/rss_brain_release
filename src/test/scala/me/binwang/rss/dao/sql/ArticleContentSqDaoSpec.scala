package me.binwang.rss.dao.sql

import cats.effect.unsafe.IORuntime
import me.binwang.rss.generator.ArticleContents
import me.binwang.rss.generator.ConnectionPoolManager.connectionPool
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class ArticleContentSqDaoSpec extends AnyFunSpec with BeforeAndAfterEach with BeforeAndAfterAll with Matchers{

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val articleContentDao = new ArticleContentSqlDao

  override def beforeAll(): Unit = {
    articleContentDao.dropTable().unsafeRunSync()
    articleContentDao.createTable().unsafeRunSync()
  }

  override def beforeEach(): Unit = {
    articleContentDao.deleteAll().unsafeRunSync()
  }

  describe("ArticleContent SQL DAO") {

    it("should insert and get article content") {
      val articleContent = ArticleContents.get()
      articleContentDao.insertOrUpdate(articleContent).unsafeRunSync() shouldBe true
      articleContentDao.get(articleContent.id).unsafeRunSync().get shouldBe articleContent
    }

    it("should get none if no content found") {
     articleContentDao.get("testid").unsafeRunSync() shouldBe None
    }

    it("should update if exists") {
      val c1 = ArticleContents.get()
      val c2 = ArticleContents.get(Some(c1.id))
      c1.id shouldBe c2.id
      articleContentDao.insertOrUpdate(c1).unsafeRunSync()
      articleContentDao.insertOrUpdate(c2).unsafeRunSync()
      articleContentDao.get(c1.id).unsafeRunSync().get shouldBe c2
    }

  }

}
