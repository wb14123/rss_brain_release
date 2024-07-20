package me.binwang.rss.dao.elasticsearch

import cats.effect.IO
import com.sksamuel.elastic4s.cats.effect.instances._
import com.sksamuel.elastic4s.circe._
import com.sksamuel.elastic4s.requests.searches.SearchResponse
import com.sksamuel.elastic4s.requests.searches.knn.Knn
import com.sksamuel.elastic4s.requests.searches.queries.Query
import com.sksamuel.elastic4s.{ElasticClient, ElasticDate, ElasticDsl, Response}
import io.circe.generic.auto._
import me.binwang.rss.dao.{ArticleSearchDao, FolderSourceDao}
import me.binwang.rss.model.ID.ID
import me.binwang.rss.model.{Article, SearchOptions, TermWeight}
import me.binwang.rss.util.IOToStream.seqToStream

import java.time.ZonedDateTime
import scala.language.implicitConversions

class ArticleSearchElasticDao(val moreLikeThisSearchBoost: Double, val moreLikeThisKnnBoost: Double,
    val searchMinScore: Double = 0.1)(implicit val elasticClient: ElasticClient,
    implicit val folderSourceDao: FolderSourceDao) extends ArticleSearchDao with BaseElasticDao {


  // do nothing since table is created by ArticleElasticDao
  override def createTable(): IO[Unit] = IO.unit

  override protected val indexName: String = ArticleElasticDao.indexName

  override def searchForUser(userID: String, searchOptions: SearchOptions): fs2.Stream[IO, Article] = {
    val sources = folderSourceDao.getSourcesByUser(userID)
    searchInSourceStream(sources.map(_.source.id), searchOptions)
  }

  override def searchInFolder(folderID: String, searchOptions: SearchOptions): fs2.Stream[IO, Article] = {
    val sources = folderSourceDao.getSourcesByFolder(folderID)
    searchInSourceStream(sources.map(_.source.id), searchOptions)
  }

  override def searchInSource(sourceID: String, searchOptions: SearchOptions): fs2.Stream[IO, Article] = {
    searchInSources(Seq(sourceID), searchOptions)
  }

  override def moreLikeThisForUser(articleID: String, userID: String, start: Int, limit: Int,
      postedBefore: Option[ZonedDateTime], postedAfter: Option[ZonedDateTime]): fs2.Stream[IO, Article] = {
    val sources = folderSourceDao.getSourcesByUser(userID)
    moreLikeThisInSources(articleID, sources.map(_.source.id), start, limit, postedBefore, postedAfter)
  }

  override def moreLikeThisInFolder(articleID: String, folderID: String, start: Int, limit: Int,
      postedBefore: Option[ZonedDateTime], postedAfter: Option[ZonedDateTime]): fs2.Stream[IO, Article] = {
    val sources = folderSourceDao.getSourcesByFolder(folderID)
    moreLikeThisInSources(articleID, sources.map(_.source.id), start, limit, postedBefore, postedAfter)
  }

  override def moreLikeThisInSource(articleID: String, sourceID: String, start: Int, limit: Int,
      postedBefore: Option[ZonedDateTime], postedAfter: Option[ZonedDateTime]): fs2.Stream[IO, Article] = {
    moreLikeThisInSources(articleID, fs2.Stream.eval(IO.pure(sourceID)), start, limit, postedBefore, postedAfter)
  }


  override def getTermVector(articleID: String, size: Int): IO[Seq[TermWeight]] = {
    getTermVectorForField(articleID, Seq("title", "content"), 1).map {
      _.toSeq.map(kv => TermWeight(kv._1, kv._2)).sortBy(_.weight)(Ordering[Double].reverse).take(size)
    }
  }


  override def updateTitleEmbedding(articleID: String, embedding: Seq[Double]): IO[Boolean] = {
    import com.sksamuel.elastic4s.ElasticDsl._
    elasticClient.execute {
      updateById(indexName, articleID).doc(ArticleElasticDao.titleEmbeddingVectorField -> embedding)
    }.map(_.result.found).handleError {
      case _: NoSuchElementException => false
      case e => throw e
    }
  }

  override def getTitleEmbedding(articleID: String): IO[Option[Seq[Double]]] = {
    import com.sksamuel.elastic4s.ElasticDsl._
    elasticClient.execute {
      ElasticDsl.get(indexName, articleID)
    }.map { res =>
      if (res.isSuccess && res.result.exists) {
        res.result.sourceFieldOpt(ArticleElasticDao.titleEmbeddingVectorField).map(_.asInstanceOf[Seq[Double]])
      } else {
        None
      }
    }
  }

  private def moreLikeThisInSources(articleID: String, sourceIDStream: fs2.Stream[IO, String], start: Int, limit: Int,
      postedBefore: Option[ZonedDateTime], postedAfter: Option[ZonedDateTime]): fs2.Stream[IO, Article] = {
    val result = getMoreLikeThisQueryString(articleID).flatMap { queryStr =>
      getTitleEmbedding(articleID).map { embedding =>
        if (queryStr.trim.isEmpty) {
          fs2.Stream.empty
        } else {
          searchInSourceStream(sourceIDStream,
            SearchOptions(queryStr, start, limit, sortByTime = false, postedAfter = postedAfter,
              postedBefore = postedBefore),
            Some(articleID),
            embedding,
          ).filter(!_.id.equals(articleID))
        }
      }
    }
    fs2.Stream.eval(result).flatten
  }

  private def getTermVectorForField(articleID: String, fields: Seq[String], minDocFreq: Int): IO[Map[String, Double]] = {
    import com.sksamuel.elastic4s.ElasticDsl._
    elasticClient.execute {
      termVectors(indexName, articleID)
        .fields(fields)
        .fieldStatistics(false)
        .termStatistics(false)
        .payloads(false)
        .positions(false)
        .minDocFreq(minDocFreq)
    }
      .map(_.result.termVectors)
      .map(_.values
        .toSeq
        .flatMap(_.terms
          .view.mapValues(_.score)
          .toSeq
        )
        .groupBy(_._1)
        .view.mapValues(_.map(_._2).sum)
        .toMap
      )
  }

  private def getMoreLikeThisQueryString(articleID: String): IO[String] = {
    // exclude source title terms from content so that it doesn't always find the articles in the same source
    getTermVectorForField(articleID, Seq("sourceTitle"), 1).flatMap { sourceTitleTerms =>
      getTermVectorForField(articleID, Seq("title", "content"), 2).map { contentTerms =>
        contentTerms
          .filter{ case (k, _) => !sourceTitleTerms.contains(k)}
          .toSeq
          .sortBy { case (_, v) => v} (Ordering[Double].reverse)
          .take(20)
          .map {case (k, v) => s"$k^$v"}
          .mkString(" ")
      }
    }
  }

  private def searchInSourceStream(sourceIDStream: fs2.Stream[IO, String],
      searchOptions: SearchOptions, excludeArticleID: Option[ID] = None,
      embeddingVector: Option[Seq[Double]] = None): fs2.Stream[IO, Article] = {
    val sourceIDs = sourceIDStream.compile.toList
    fs2.Stream.eval(sourceIDs).flatMap{ids =>
      searchInSources(ids, searchOptions, excludeArticleID, embeddingVector)}
  }

  private def searchInSources(sourceIDs: Seq[String], searchOptions: SearchOptions,
      excludeArticleID: Option[ID] = None, embeddingVector: Option[Seq[Double]] = None): fs2.Stream[IO, Article] = {
    import com.sksamuel.elastic4s.ElasticDsl._
    val result: IO[Response[SearchResponse]] = elasticClient.execute {
      val filters: Seq[Query] = Seq(
        Some(termsQuery("sourceID", sourceIDs.toSet)),
        searchOptions.postedAfter.map(t => rangeQuery("postedAt").gt(
          ElasticDate.fromTimestamp(t.toInstant.toEpochMilli))),
        searchOptions.postedBefore.map(t => rangeQuery("postedAt").lt(
            ElasticDate.fromTimestamp(t.toInstant.toEpochMilli))),
        excludeArticleID.map(articleID => not(termsQuery("id", articleID)))
      ).filter(_.isDefined).map(_.get)
      var filter = boolQuery()
        .filter(filters)
        .must(queryStringQuery(searchOptions.query).field("title").field("content"))
      if (embeddingVector.isDefined) {
        filter = filter.boost(moreLikeThisSearchBoost)
      }
      var q = search(indexName)
        .query(filter)
        .start(searchOptions.start)
        .limit(searchOptions.limit)
        .minScore(searchMinScore)
      if (embeddingVector.isDefined) {
        q = q.knn(Knn(
          field = ArticleElasticDao.titleEmbeddingVectorField,
          numCandidates = searchOptions.limit,
          queryVector = embeddingVector.get,
          k = searchOptions.limit,
          boost = moreLikeThisKnnBoost
        ).filter(boolQuery().filter(filters)))
      }
      if (searchOptions.sortByTime) {
        q = q.sortByFieldDesc("postedAt")
      }
      if (searchOptions.highlight) {
        q = q.highlighting(highlight("content")
          .preTag("<b>")
          .postTag("</b>")
        )
      }
      q
    }
    seqToStream(result.map{ response =>
      val articles = response.result.to[Article]
      if (!searchOptions.highlight) {
        articles
      } else {
        val highlightedContent = response.result.hits.hits.map(_.highlightFragments("content").mkString(" ... "))
        articles
          .zip(highlightedContent)
          .map {case (article, content) => article.copy(description = content)}
      }
    })
  }
}
