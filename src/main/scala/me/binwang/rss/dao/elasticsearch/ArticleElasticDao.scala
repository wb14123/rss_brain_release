package me.binwang.rss.dao.elasticsearch

import cats.effect.IO
import com.sksamuel.elastic4s.analysis.{Analysis, CustomAnalyzer}
import com.sksamuel.elastic4s.cats.effect.instances._
import com.sksamuel.elastic4s.circe._
import com.sksamuel.elastic4s.fields._
import com.sksamuel.elastic4s.{ElasticClient, ElasticDsl}
import com.typesafe.config.ConfigFactory
import io.circe.generic.auto._
import me.binwang.rss.dao.ArticleDao
import me.binwang.rss.model.ID.ID
import me.binwang.rss.model.{Article, ArticleWithUserMarking}

import java.time.ZonedDateTime

object ArticleElasticDao {
  private val config = ConfigFactory.load()
  private val replicas: Int = config.getInt("elasticsearch.replicas")
  private val shards: Int = config.getInt("elasticsearch.shards")

  val indexName: String = ElasticSearchClient.indexPrefix + "articles"
  private val readByUsersField: String = "readByUsers"
  val titleEmbeddingVectorField: String = "titleEmbedding"
  val titleEmbeddingVectorSize: Int = 768

  def createTable()(implicit elasticClient: ElasticClient): IO[Unit] = {
    import com.sksamuel.elastic4s.ElasticDsl._

    val result = elasticClient.execute {
      createIndex(ArticleElasticDao.indexName).shards(shards).replicas(replicas).mapping(
        properties(
          KeywordField("id"),
          TextField("title"),
          KeywordField("sourceID"),
          TextField("sourceTitle"),
          KeywordField("guid"),
          KeywordField("link"),
          DateField("createdAt"),
          DateField("postedAt"),
          TextField("description"),
          TextField("content", analyzer = Some("htmlStripAnalyzer")),
          KeywordField("author"),
          IntegerField("comments"),
          IntegerField("upVotes"),
          IntegerField("downVotes"),
          DoubleField("score", nullValue = Some(0.0)),
          KeywordField(readByUsersField),
          ObjectField("mediaGroups"),
          BooleanField("nsfw"),
          BooleanField("postedAtIsMissing"),

          // using paraphrase-multilingual-mpnet-base-v2 from SentenceTransformers
          DenseVectorField(titleEmbeddingVectorField, titleEmbeddingVectorSize, index = true, similarity = L2Norm)
        )
      ).analysis(
        Analysis(CustomAnalyzer(
          "htmlStripAnalyzer",
          tokenizer = "smartcn_tokenizer",
          tokenFilters = List("lowercase", "porter_stem", "smartcn_stop"),
          charFilters = List("html_strip"),
        ))
      )
    }

    result.map(_ => ())
  }
}

class ArticleElasticDao(implicit val elasticClient: ElasticClient) extends ArticleDao with BaseElasticDao {

  override protected val indexName: String = ArticleElasticDao.indexName

  override def createTable(): IO[Unit] = ArticleElasticDao.createTable()

  override def get(id: ID): IO[Option[Article]] = {
    import com.sksamuel.elastic4s.ElasticDsl._
    elasticClient.execute {
      ElasticDsl.get(indexName, id)
    }.map{ res =>
      if (res.isSuccess && res.result.exists) {
        Some(res.result.to[Article])
      } else {
        None
      }
    }
  }

  override def listBySource(sourceID: ID, size: Int, postedBefore: ZonedDateTime, articleID: ID): fs2.Stream[IO, Article] = {
    throw new NotImplementedError()
  }

  override def listBySourceWithUserMarking(sourceID: ID, size: Int, postedBefore: ZonedDateTime, articleID: ID,
      userID: String, read: Option[Boolean], bookmarked: Option[Boolean],
      deleted: Option[Boolean]): fs2.Stream[IO, ArticleWithUserMarking] = {
    throw new NotImplementedError()
  }

  override def listByFolder(folderID: String, size: Int, postedBefore: ZonedDateTime, articleID: ID
      ): fs2.Stream[IO, Article] = {
    throw new NotImplementedError()
  }

  override def listByFolderWithUserMarking(folderID: String, size: Int, postedBefore: ZonedDateTime, articleID: ID,
      userID: String, read: Option[Boolean], bookmarked: Option[Boolean],
      deleted: Option[Boolean]): fs2.Stream[IO, ArticleWithUserMarking] = {
    throw new NotImplementedError()
  }

  override def listByUser(userID: String, size: Int, postedBefore: ZonedDateTime, articleID: ID): fs2.Stream[IO, Article] = {
    throw new NotImplementedError()
  }

  override def listByUserWithUserMarking(userID: String, size: Int, postedBefore: ZonedDateTime,
      articleID: ID, read: Option[Boolean], bookmarked: Option[Boolean],
      deleted: Option[Boolean]): fs2.Stream[IO, ArticleWithUserMarking] = {
    throw new NotImplementedError()
  }

  override def insertOrUpdate(article: Article): IO[Boolean] = {
    import com.sksamuel.elastic4s.ElasticDsl._
    elasticClient.execute {
      updateById(indexName, article.id).doc(article).upsert(article)
    }.map(_.result.found)
  }

  override def listBySourceOrderByScoreWithUserMarking(sourceID: ID, size: Int, maxScore: Double, articleID: ID,
      userID: String, read: Option[Boolean], bookmarked: Option[Boolean],
      deleted: Option[Boolean]): fs2.Stream[IO, ArticleWithUserMarking] = {
    throw new NotImplementedError()
  }

  override def listByFolderOrderByScoreWithUserMarking(folderID: String, size: Int, maxScore: Double, articleID: ID,
      userID: String, read: Option[Boolean], bookmarked: Option[Boolean],
      deleted: Option[Boolean]): fs2.Stream[IO, ArticleWithUserMarking] = {
    throw new NotImplementedError()
  }
}
