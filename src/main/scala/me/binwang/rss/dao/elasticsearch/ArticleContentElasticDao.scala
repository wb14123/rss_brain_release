package me.binwang.rss.dao.elasticsearch

import cats.effect.IO
import io.circe.generic.auto._
import com.sksamuel.elastic4s.circe._
import com.sksamuel.elastic4s.{ElasticClient, ElasticDsl}
import com.sksamuel.elastic4s.cats.effect.instances._
import me.binwang.rss.dao.ArticleContentDao
import me.binwang.rss.model.ArticleContent
import me.binwang.rss.model.ID.ID

class ArticleContentElasticDao(implicit val elasticClient: ElasticClient) extends ArticleContentDao with BaseElasticDao {
  override protected val indexName: String = ArticleElasticDao.indexName

  override def createTable(): IO[Unit] = ArticleElasticDao.createTable()

  override def get(id: ID): IO[Option[ArticleContent]] = {
    import com.sksamuel.elastic4s.ElasticDsl._
    elasticClient.execute {
      ElasticDsl.get(indexName, id)
    }.map{ res =>
      if (res.isSuccess && res.result.exists) {
        Some(res.result.to[ArticleContent])
      } else {
        None
      }
    }
  }

  override def insertOrUpdate(content: ArticleContent): IO[Boolean] = {
    import com.sksamuel.elastic4s.ElasticDsl._
    elasticClient.execute {
      updateById(indexName, content.id).doc(content).upsert(content)
    }.map(_.isSuccess)
  }

  override def batchGet(ids: Seq[ID]): fs2.Stream[IO, ArticleContent] = {
    throw new NotImplementedError()
  }
}
