package me.binwang.rss.dao.sql

import cats.effect.unsafe.IORuntime
import me.binwang.rss.generator.Articles
import me.binwang.rss.model.{ArticleUpdater, ContentCompleteness, FolderSourceMapping, MediaContent, MediaGroup,
  MediaGroups, SourceID}
import me.binwang.rss.generator.ConnectionPoolManager.connectionPool
import org.scalacheck.Gen
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

class ArticleSqlDaoSpec extends AnyFunSpec with BeforeAndAfterEach with BeforeAndAfterAll with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val articleDao = new ArticleSqlDao
  private val folderSourceDao = new FolderSourceSqlDao

  override def beforeAll(): Unit = {
    articleDao.dropTable().unsafeRunSync()
    folderSourceDao.dropTable().unsafeRunSync()
    articleDao.createTable().unsafeRunSync()
    folderSourceDao.createTable().unsafeRunSync()
  }

  override def beforeEach(): Unit = {
    articleDao.deleteAll().unsafeRunSync()
    folderSourceDao.deleteAll().unsafeRunSync()
  }

  describe("Article SQL DAO") {

    it("should insert and get data") {
      val article = Articles.get()
      articleDao.insertOrUpdate(article).unsafeRunSync() shouldBe true
      articleDao.get(article.id).unsafeRunSync().get shouldBe article
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

    it("should update if exist") {
      val article1 = Articles.get()
      val article2 = Articles.get(Some(article1.sourceID), Some(article1.guid))
      article1.id shouldBe article2.id
      articleDao.insertOrUpdate(article1).unsafeRunSync()
      articleDao.insertOrUpdate(article2).unsafeRunSync()
      articleDao.get(article1.id).unsafeRunSync().get.title shouldBe article2.title
    }

    it("should insert and get full content url and content completeness") {
      val article = Articles.get().copy(
        fullContentUrl = Some("http://opengarden.example.com:9020/articles?article_url=abc"),
        contentCompleteness = Some(ContentCompleteness.PARTIAL),
      )
      articleDao.insertOrUpdate(article).unsafeRunSync() shouldBe true
      articleDao.get(article.id).unsafeRunSync().get shouldBe article
    }

    it("should insert and get article without full content url and content completeness") {
      val article = Articles.get().copy(fullContentUrl = None, contentCompleteness = None)
      articleDao.insertOrUpdate(article).unsafeRunSync() shouldBe true
      val stored = articleDao.get(article.id).unsafeRunSync().get
      stored.fullContentUrl shouldBe None
      stored.contentCompleteness shouldBe None
    }

    it("should update content completeness when posted at matches") {
      val article = Articles.get().copy(contentCompleteness = Some(ContentCompleteness.PARTIAL))
      articleDao.insertOrUpdate(article).unsafeRunSync() shouldBe true
      articleDao.update(article.id,
        ArticleUpdater(contentCompleteness = Some(Some(ContentCompleteness.FULL))),
        Some(article.postedAt)).unsafeRunSync() shouldBe true
      articleDao.get(article.id).unsafeRunSync().get.contentCompleteness shouldBe Some(ContentCompleteness.FULL)
    }

    it("should not update content completeness when posted at does not match") {
      val article = Articles.get().copy(contentCompleteness = Some(ContentCompleteness.PARTIAL))
      articleDao.insertOrUpdate(article).unsafeRunSync() shouldBe true
      articleDao.update(article.id,
        ArticleUpdater(contentCompleteness = Some(Some(ContentCompleteness.FULL))),
        Some(article.postedAt.minusSeconds(1))).unsafeRunSync() shouldBe false
      articleDao.get(article.id).unsafeRunSync().get.contentCompleteness shouldBe Some(ContentCompleteness.PARTIAL)
    }

    it("should keep content completeness that the updater leaves empty") {
      val article = Articles.get().copy(contentCompleteness = Some(ContentCompleteness.FULL))
      articleDao.insertOrUpdate(article).unsafeRunSync() shouldBe true
      articleDao.update(article.id, ArticleUpdater(), Some(article.postedAt)).unsafeRunSync() shouldBe false
      articleDao.get(article.id).unsafeRunSync().get.contentCompleteness shouldBe Some(ContentCompleteness.FULL)
    }

    it("should get articles by source") {
      val sourceID = SourceID("http://localhost/test.xml")
      val totalSize = 20
      val fetchSize = 5
      fetchSize < totalSize shouldBe true
      (1 to totalSize).foreach { _ =>
        val article = Articles.get(Some(sourceID), None)
        articleDao.insertOrUpdate(article).unsafeRunSync()
      }
      val articles = articleDao.listBySource(sourceID, fetchSize, ZonedDateTime.now()).compile.toList.unsafeRunSync()
      articles.size shouldBe fetchSize
      articles.reduce{(a1, a2) =>
        a1.postedAt.isAfter(a2.postedAt) shouldBe true
        a2
      }
      val oldArticles = articleDao.listBySource(sourceID, fetchSize, ZonedDateTime.now().minusYears(10))
        .compile.toList.unsafeRunSync()
      oldArticles.size shouldBe 0
    }

    it("should get articles by folder") {
      val folderID = Gen.uuid.sample.get.toString
      val totalSize = 20
      val fetchSize = 5
      fetchSize < totalSize shouldBe true
      (1 to totalSize).foreach { i =>
        val sourceID = SourceID(Gen.uuid.sample.get.toString)
        val article = Articles.get(Some(sourceID), None)
        articleDao.insertOrUpdate(article).unsafeRunSync()
        folderSourceDao.addSourceToFolder(FolderSourceMapping(folderID, sourceID, "userID", i)).unsafeRunSync()
      }
      val articles = articleDao.listByFolder(folderID, fetchSize, ZonedDateTime.now()).compile.toList.unsafeRunSync()
      articles.size shouldBe fetchSize
      articles.reduce{(a1, a2) =>
        a1.postedAt.isBefore(a2.postedAt) shouldBe false
        a2
      }
      val oldArticles = articleDao.listByFolder(folderID, fetchSize, ZonedDateTime.now().minusYears(10))
        .compile.toList.unsafeRunSync()
      oldArticles.size shouldBe 0
    }

    it("should get articles when posted at is the same") {
      val sourceID = SourceID("http://localhost/test.xml")
      val totalSize = 20
      val fetchSize = 5
      val postedAt = ZonedDateTime.now
      fetchSize < totalSize shouldBe true
      (1 to totalSize).foreach { i =>
        val article = Articles.get(Some(sourceID), None, Some(postedAt.minusDays(i / 7)))
        articleDao.insertOrUpdate(article).unsafeRunSync() shouldBe true
      }
      val articles = articleDao.listBySource(sourceID, fetchSize, ZonedDateTime.now()).compile.toList.unsafeRunSync()
      articles.size shouldBe fetchSize
      articles.reduce{(a1, a2) =>
        a1.postedAt == a2.postedAt shouldBe true
        a1.id < a2.id
        a2
      }
      val secondArticles = articleDao.listBySource(sourceID, fetchSize, postedAt, articles.last.id)
        .compile.toList.unsafeRunSync()
      secondArticles.size shouldBe fetchSize
      secondArticles.head.id > articles.last.id
      secondArticles.reduce{(a1, a2) =>
        a1.postedAt.isAfter(a2.postedAt) || a1.postedAt.isEqual(a2.postedAt) shouldBe true
        a1.id < a2.id
        a2
      }
      val oldArticles = articleDao.listBySource(sourceID, fetchSize, ZonedDateTime.now().minusYears(10))
        .compile.toList.unsafeRunSync()
      oldArticles.size shouldBe 0
    }

  }

}
