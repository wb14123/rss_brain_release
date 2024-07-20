package me.binwang.rss.dao.elasticsearch

import cats.effect.unsafe.IORuntime
import com.sksamuel.elastic4s.ElasticClient
import me.binwang.rss.dao.FolderSourceDao
import me.binwang.rss.dao.sql.{FolderSourceSqlDao, SourceSqlDao}
import me.binwang.rss.generator.{Articles, Folders, Sources, Users}
import me.binwang.rss.model.{ArticleContent, FolderSourceMapping, SearchOptions}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import me.binwang.rss.generator.ConnectionPoolManager.connectionPool

import java.time.ZonedDateTime

class ArticleSearchElasticDaoSpec extends AnyFunSpec with BeforeAndAfterEach with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global

  private implicit val elasticClient: ElasticClient = ElasticSearchClient()
  private implicit val folderSourceDao: FolderSourceDao = new FolderSourceSqlDao()
  private val sourceDao = new SourceSqlDao()
  private val articleDao = new ArticleElasticDao()
  private val articleContentDao = new ArticleContentElasticDao()
  private var articleSearchDao = new ArticleSearchElasticDao(1.0, 0.0)

  override def beforeEach(): Unit = {
    sourceDao.dropTable().unsafeRunSync()
    sourceDao.createTable().unsafeRunSync()
    folderSourceDao.dropTable().unsafeRunSync()
    folderSourceDao.createTable().unsafeRunSync()
    articleDao.dropTable().unsafeRunSync()
    articleDao.createTable().unsafeRunSync()
    articleContentDao.dropTable().unsafeRunSync()
    articleContentDao.createTable().unsafeRunSync()
  }


  describe("Article Search Elastic DAO") {

    it("should search articles in source") {
      val source = Sources.get()
      val article = Articles.get(Some(source.id)).copy(title = "apple banana cat")
      val articleContent = ArticleContent(article.id, "apple dog egg")
      articleDao.insertOrUpdate(article).unsafeRunSync()
      articleContentDao.insertOrUpdate(articleContent).unsafeRunSync()
      Thread.sleep(1000) // give some time for article to be indexed

      val testQueries = Seq("apple", "banana", "dog", "banana dog", "apple fog", "Apple")

      testQueries.foreach { q =>
        val results = articleSearchDao.searchInSource(source.id, SearchOptions(q, 0, 10))
          .compile.toList.unsafeRunSync()
        withClue(q + " should hit article") {
          results.size shouldBe 1
          results.head.id shouldBe article.id
        }
      }

      val noHitQueries = Seq("game", "this is a long query", source.id, article.id)
      noHitQueries.foreach { q =>
        val results = articleSearchDao.searchInSource(source.id, SearchOptions(q, 0, 10))
          .compile.toList.unsafeRunSync()
        withClue(q + " should not hit article") {results.size shouldBe 0}
      }

      val results = articleSearchDao.searchInSource(Sources.get().id, SearchOptions("sourceID:" + source.id,  0, 10))
        .compile.toList.unsafeRunSync()
      withClue("wrong source id should not hit article") {results.size shouldBe 0}
    }

    it("should not hit articles for wrong source ID") {
      val source = Sources.get()
      val article = Articles.get(Some(source.id)).copy(title = "apple banana cat")
      val articleContent = ArticleContent(article.id, "apple dog egg")
      articleDao.insertOrUpdate(article).unsafeRunSync()
      articleContentDao.insertOrUpdate(articleContent).unsafeRunSync()
      Thread.sleep(1000) // give some time for article to be indexed

      val invalidSourceIDs = Seq(source.id.toUpperCase, source.id + "a", "a" + source.id.tail)
      invalidSourceIDs.foreach { sourceID =>
        val results = articleSearchDao.searchInSource(sourceID, SearchOptions("apple", 0, 10))
          .compile.toList.unsafeRunSync()
        withClue(sourceID + " should not hit article") {results.size shouldBe 0}
      }
    }

    it("should search Chinese") {
      val source = Sources.get()
      val article = Articles.get(Some(source.id)).copy(title = "I have an apple 我有一个苹果")
      val articleContent = ArticleContent(article.id, "I have an apple 我有一个苹果")
      articleDao.insertOrUpdate(article).unsafeRunSync()
      articleContentDao.insertOrUpdate(articleContent).unsafeRunSync()
      Thread.sleep(1000) // give some time for article to be indexed

      val testQueries = Seq("apple", "Apple", "苹果")

      testQueries.foreach { q =>
        val results = articleSearchDao.searchInSource(source.id, SearchOptions(q, 0, 10))
          .compile.toList.unsafeRunSync()
        withClue(q + " should hit article") {
          results.size shouldBe 1
          results.head.id shouldBe article.id
        }
      }

      val noHitQueries = Seq("game", "this is a long query", source.id, article.id, "香蕉")
      noHitQueries.foreach { q =>
        val results = articleSearchDao.searchInSource(source.id, SearchOptions(q, 0, 10))
          .compile.toList.unsafeRunSync()
        withClue(q + " should not hit article") {results.size shouldBe 0}
      }
    }

    it("should search articles in folder") {
      val source = Sources.get()
      val user = Users.get("user@example.com")
      val folder1 = Folders.get(user.id, 0)
      val folder2 = Folders.get(user.id, 1)

      val article = Articles.get(Some(source.id)).copy(title = "apple banana cat")
      val articleContent = ArticleContent(article.id, "apple dog egg")

      articleDao.insertOrUpdate(article).unsafeRunSync()
      articleContentDao.insertOrUpdate(articleContent).unsafeRunSync()
      sourceDao.insert(source).unsafeRunSync()
      folderSourceDao.addSourceToFolder(FolderSourceMapping(folder1.id, source.id, user.id, 0)).unsafeRunSync()

      Thread.sleep(1000) // give some time for article to be indexed

      val testQueries = Seq("apple", "banana", "dog", "banana dog", "apple fog", "Apple")

      testQueries.foreach { q =>
        var results = articleSearchDao.searchInFolder(folder1.id, SearchOptions(q, 0, 10))
          .compile.toList.unsafeRunSync()
        withClue("should search article in " + folder1.id) {
          results.size shouldBe 1
          results.head.id shouldBe article.id
        }

        results = articleSearchDao.searchInFolder(folder2.id, SearchOptions(q, 0, 10))
          .compile.toList.unsafeRunSync()
        withClue("should not search article in " + folder2.id) {
          results.size shouldBe 0
        }
      }
    }

    it("should search articles in user") {
      val source = Sources.get()
      val user = Users.get("user@example.com")
      val folder1 = Folders.get(user.id, 0)

      val article = Articles.get(Some(source.id)).copy(title = "apple banana cat")
      val articleContent = ArticleContent(article.id, "apple dog egg")

      articleDao.insertOrUpdate(article).unsafeRunSync()
      articleContentDao.insertOrUpdate(articleContent).unsafeRunSync()
      sourceDao.insert(source).unsafeRunSync()
      folderSourceDao.addSourceToFolder(FolderSourceMapping(folder1.id, source.id, user.id, 0)).unsafeRunSync()

      Thread.sleep(1000) // give some time for article to be indexed

      val testQueries = Seq("apple", "banana", "dog", "banana dog", "apple fog", "Apple")

      testQueries.foreach { q =>
        val results = articleSearchDao.searchForUser(user.id, SearchOptions(q, 0, 10))
          .compile.toList.unsafeRunSync()
        withClue("should search article in " + folder1.id) {
          results.size shouldBe 1
          results.head.id shouldBe article.id
        }
      }
    }

    it("should get articles more like this in source") {
      val source = Sources.get()
      val now = ZonedDateTime.now()
      val article1 = Articles.get(Some(source.id)).copy(title = "apple banana cat", postedAt = now.plusYears(-2))
      val article2 = Articles.get(Some(source.id)).copy(title = "apple banana dog", postedAt = now.plusYears(-1))
      val article3 = Articles.get(Some(source.id)).copy(title = "apple dog egg", postedAt = now)
      val articleContent1 = ArticleContent(article1.id, "<p>apple banana cat</p>")
      val articleContent2 = ArticleContent(article2.id, "<p>apple banana dog</p>")
      val articleContent3 = ArticleContent(article3.id, "<p>apple dog egg</p>")
      articleDao.insertOrUpdate(article1).unsafeRunSync()
      articleContentDao.insertOrUpdate(articleContent1).unsafeRunSync()
      articleDao.insertOrUpdate(article2).unsafeRunSync()
      articleContentDao.insertOrUpdate(articleContent2).unsafeRunSync()
      articleDao.insertOrUpdate(article3).unsafeRunSync()
      articleContentDao.insertOrUpdate(articleContent3).unsafeRunSync()
      Thread.sleep(1000) // give some time for article to be indexed

      var result = articleSearchDao.moreLikeThisInSource(article1.id, source.id, 0, 10, None, None)
        .compile.toList.unsafeRunSync()
      result.size shouldBe 2
      result.head.id shouldBe article2.id
      result(1).id shouldBe article3.id

      result = articleSearchDao.moreLikeThisInSource(article1.id, source.id, 0, 10, Some(now.plusDays(-1)), None)
        .compile.toList.unsafeRunSync()
      result.size shouldBe 1
      result.head.id shouldBe article2.id

      result = articleSearchDao.moreLikeThisInSource(article1.id, source.id, 0, 10, None, Some(now.plusDays(-1)))
        .compile.toList.unsafeRunSync()
      result.size shouldBe 1
      result.head.id shouldBe article3.id


      result = articleSearchDao.moreLikeThisInSource(article1.id, source.id, 0, 10, Some(now.plusDays(-1)), Some(now.plusDays(-400)))
        .compile.toList.unsafeRunSync()
      result.size shouldBe 1
      result.head.id shouldBe article2.id


      result = articleSearchDao.moreLikeThisInSource(article1.id, source.id, 0, 10, Some(now.plusDays(-1)), Some(now.plusDays(-2)))
        .compile.toList.unsafeRunSync()
      result.size shouldBe 0
    }

    it("should update title embeddings") {
      val source = Sources.get()
      val article = Articles.get(Some(source.id))
      articleDao.insertOrUpdate(article).unsafeRunSync()
      val embedding = Range(0, ArticleElasticDao.titleEmbeddingVectorSize).map(_.toDouble)
      articleSearchDao.updateTitleEmbedding(article.id, embedding).unsafeRunSync() shouldBe true
    }

    it("should use knn to find more like this articles") {


      val source = Sources.get()
      val now = ZonedDateTime.now()
      val article1 = Articles.get(Some(source.id)).copy(title = "apple banana cat", postedAt = now.plusYears(-2))
      val article2 = Articles.get(Some(source.id)).copy(title = "apple banana dog", postedAt = now.plusYears(-1))
      val article3 = Articles.get(Some(source.id)).copy(title = "apple dog egg", postedAt = now)
      val articleContent1 = ArticleContent(article1.id, "<p>apple banana cat</p>")
      val articleContent2 = ArticleContent(article2.id, "<p>apple banana dog</p>")
      val articleContent3 = ArticleContent(article3.id, "<p>apple dog egg</p>")
      articleDao.insertOrUpdate(article1).unsafeRunSync()
      articleContentDao.insertOrUpdate(articleContent1).unsafeRunSync()
      articleDao.insertOrUpdate(article2).unsafeRunSync()
      articleContentDao.insertOrUpdate(articleContent2).unsafeRunSync()
      articleDao.insertOrUpdate(article3).unsafeRunSync()
      articleContentDao.insertOrUpdate(articleContent3).unsafeRunSync()

      articleSearchDao.updateTitleEmbedding(article1.id, Seq.fill(ArticleElasticDao.titleEmbeddingVectorSize)(1.0)).unsafeRunSync()
      articleSearchDao.updateTitleEmbedding(article2.id, Seq.fill(ArticleElasticDao.titleEmbeddingVectorSize)(0.0)).unsafeRunSync()
      articleSearchDao.updateTitleEmbedding(article3.id, Seq.fill(ArticleElasticDao.titleEmbeddingVectorSize)(1.1)).unsafeRunSync()
      Thread.sleep(2000) // give some time for article to be indexed

      // use pure knn
      articleSearchDao = new ArticleSearchElasticDao(0.0, 1.0)
      var result = articleSearchDao.moreLikeThisInSource(article1.id, source.id, 0, 10, None, None)
        .compile.toList.unsafeRunSync()
      // if knn is used, article 3 should be closer than article 2
      result.size shouldBe 2
      result.head.id shouldBe article3.id
      result(1).id shouldBe article2.id


      // combine knn and search, but give knn a much larger boost
      articleSearchDao = new ArticleSearchElasticDao(0.0, 1.0)
      result = articleSearchDao.moreLikeThisInSource(article1.id, source.id, 0, 10, None, None)
        .compile.toList.unsafeRunSync()
      result.size shouldBe 2
      result.head.id shouldBe article3.id
      result(1).id shouldBe article2.id
    }


    it("should apply filters on knn") {
      val source = Sources.get()
      val source2 = Sources.get()
      val now = ZonedDateTime.now()
      val article1 = Articles.get(Some(source.id)).copy(title = "apple banana cat", postedAt = now.plusYears(-2))
      val article2 = Articles.get(Some(source2.id)).copy(title = "apple banana dog", postedAt = now.plusYears(-1))
      val article3 = Articles.get(Some(source.id)).copy(title = "apple dog egg", postedAt = now)
      val articleContent1 = ArticleContent(article1.id, "<p>apple banana cat</p>")
      val articleContent2 = ArticleContent(article2.id, "<p>apple banana dog</p>")
      val articleContent3 = ArticleContent(article3.id, "<p>apple dog egg</p>")
      articleDao.insertOrUpdate(article1).unsafeRunSync()
      articleContentDao.insertOrUpdate(articleContent1).unsafeRunSync()
      articleDao.insertOrUpdate(article2).unsafeRunSync()
      articleContentDao.insertOrUpdate(articleContent2).unsafeRunSync()
      articleDao.insertOrUpdate(article3).unsafeRunSync()
      articleContentDao.insertOrUpdate(articleContent3).unsafeRunSync()

      articleSearchDao.updateTitleEmbedding(article1.id, Seq.fill(ArticleElasticDao.titleEmbeddingVectorSize)(1.0)).unsafeRunSync()
      articleSearchDao.updateTitleEmbedding(article2.id, Seq.fill(ArticleElasticDao.titleEmbeddingVectorSize)(0.0)).unsafeRunSync()
      articleSearchDao.updateTitleEmbedding(article3.id, Seq.fill(ArticleElasticDao.titleEmbeddingVectorSize)(1.1)).unsafeRunSync()
      Thread.sleep(2000) // give some time for article to be indexed

      // use pure knn
      articleSearchDao = new ArticleSearchElasticDao(0.0, 1.0)
      val result = articleSearchDao.moreLikeThisInSource(article1.id, source2.id, 0, 10, None, None)
        .compile.toList.unsafeRunSync()
      // if knn is used, article 3 should be closer than article 2
      result.size shouldBe 1
      result.head.id shouldBe article2.id
    }

  }

}
