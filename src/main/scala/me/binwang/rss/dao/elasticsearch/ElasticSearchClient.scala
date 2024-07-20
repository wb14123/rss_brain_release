package me.binwang.rss.dao.elasticsearch

import com.sksamuel.elastic4s.http.JavaClient
import com.sksamuel.elastic4s.{ElasticClient, ElasticProperties}
import com.typesafe.config.ConfigFactory
import org.apache.http.client.config.RequestConfig
import org.elasticsearch.client.RestClientBuilder

object ElasticSearchClient {
  private val config = ConfigFactory.load()
  val indexPrefix: String = config.getString("elasticsearch.indexPrefix") + "_"

  def apply(): ElasticClient = {
    val host = config.getString("elasticsearch.host")
    val port = config.getInt("elasticsearch.port")
    val socketTimeout = config.getInt("elasticsearch.socket-timeout-ms")
    val props = ElasticProperties(s"http://$host:$port")
    ElasticClient(JavaClient(props, new RestClientBuilder.RequestConfigCallback() {
      override def customizeRequestConfig(requestConfigBuilder: RequestConfig.Builder): RequestConfig.Builder = {
        requestConfigBuilder.setSocketTimeout(socketTimeout);
      }
    }))
  }
}
