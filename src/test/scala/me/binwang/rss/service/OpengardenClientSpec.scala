package me.binwang.rss.service

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import me.binwang.rss.fetch.crawler.UrlValidator
import me.binwang.rss.model.{ArticleFullContentFetchFailed, Comment, CommentFetchFailed,
  CommentSourceNotSupported, SsrfBlockedError}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import sttp.client4.impl.cats.implicits._
import sttp.client4.testing.{BackendStub, ResponseStub}
import sttp.model.{StatusCode, Uri}
import sttp.monad.MonadAsyncError

import java.time.{Instant, ZoneId, ZonedDateTime}
import scala.concurrent.duration._

class OpengardenClientSpec extends AnyFunSpec with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val articleUrl = "https://www.reddit.com/r/technology/comments/abc123/"

  private val commentUrl = s"http://127.0.0.1:9020/comments" +
    "?profile=p&fetcher=reddit" +
    "&article_url=https%3A%2F%2Fwww.reddit.com%2Fr%2Ftechnology%2Fcomments%2Fabc123%2F"

  private val fullContentUrl = s"http://127.0.0.1:9020/articles" +
    "?profile=default&fetcher=reddit" +
    "&article_url=https%3A%2F%2Fwww.reddit.com%2Fr%2Ftechnology%2Fcomments%2Fabc123%2F"

  private val articleBody = "<div><p>the whole article</p><p>second paragraph</p></div>"

  private val body =
    s"""
      |{
      |  "articleUrl": "$articleUrl",
      |  "hasMore": true,
      |  "comments": [
      |    {
      |      "id": "k1",
      |      "body": "<p>hi</p>",
      |      "author": "someone",
      |      "authorUrl": "https://www.reddit.com/user/someone",
      |      "score": 12,
      |      "createdAt": 1712345678,
      |      "permalink": "https://www.reddit.com/r/technology/comments/abc123/k1/",
      |      "hasMoreReplies": true,
      |      "replies": [
      |        {
      |          "id": "k2",
      |          "body": "<p>a reply</p>",
      |          "author": null,
      |          "authorUrl": null,
      |          "score": null,
      |          "createdAt": null,
      |          "permalink": null,
      |          "hasMoreReplies": false,
      |          "replies": []
      |        }
      |      ]
      |    }
      |  ]
      |}""".stripMargin

  describe("OpengardenClient") {

    it("should use the comment url from the feed when there is no base url") {
      val (client, requested) = clientWithBody(None)
      client.getComments(commentUrl).unsafeRunSync()
      checkRequested(requested().get, "127.0.0.1", 9020)
    }

    it("should replace the host when a base url is configured") {
      val (client, requested) = clientWithBody(Some("http://opengarden:9020"))
      client.getComments(commentUrl).unsafeRunSync()
      checkRequested(requested().get, "opengarden", 9020)
    }

    it("should not duplicate the path separator when the base url ends with one") {
      val (client, requested) = clientWithBody(Some("http://opengarden:9020/"))
      client.getComments(commentUrl).unsafeRunSync()
      checkRequested(requested().get, "opengarden", 9020)
    }

    it("should decode comments with nested replies and unix timestamps") {
      val (client, _) = clientWithBody(None)
      val comments = client.getComments(commentUrl).unsafeRunSync()
      comments.articleUrl shouldBe articleUrl
      comments.hasMore shouldBe true
      comments.comments shouldBe Seq(Comment(
        id = "k1",
        body = "<p>hi</p>",
        author = Some("someone"),
        authorUrl = Some("https://www.reddit.com/user/someone"),
        score = Some(12),
        createdAt = Some(ZonedDateTime.ofInstant(Instant.ofEpochSecond(1712345678), ZoneId.systemDefault())),
        permalink = Some("https://www.reddit.com/r/technology/comments/abc123/k1/"),
        hasMoreReplies = true,
        replies = Seq(Comment(
          id = "k2",
          body = "<p>a reply</p>",
          author = None,
          authorUrl = None,
          score = None,
          createdAt = None,
          permalink = None,
          hasMoreReplies = false,
          replies = Seq(),
        )),
      ))
    }

    it("should decode comments that omit their optional fields") {
      val minimalBody =
        s"""{"articleUrl": "$articleUrl", "hasMore": false,
           |"comments": [{"id": "k1", "body": "<p>hi</p>", "hasMoreReplies": false, "replies": []}]}""".stripMargin
      val (client, _) = clientWithBody(None, minimalBody)
      client.getComments(commentUrl).unsafeRunSync().comments shouldBe Seq(Comment(
        id = "k1",
        body = "<p>hi</p>",
        author = None,
        authorUrl = None,
        score = None,
        createdAt = None,
        permalink = None,
        hasMoreReplies = false,
        replies = Seq(),
      ))
    }

    it("should report the error message published by the api") {
      val backend = BackendStub(implicitly[MonadAsyncError[IO]]).whenRequestMatchesPartial { _ =>
        ResponseStub.adjust("""{"error": "the fetcher reddit reads no comments"}""", StatusCode.BadRequest)
      }
      val error = new OpengardenClient(backend, None, UrlValidator.allowAll).getComments(commentUrl)
        .attempt.unsafeRunSync().swap.toOption.get
      error shouldBe a[CommentFetchFailed]
      error.getMessage shouldBe "the fetcher reddit reads no comments"
    }

    it("should fail to decode a success response that is not a comment response") {
      val (client, _) = clientWithBody(None, "not json")
      client.getComments(commentUrl).attempt.unsafeRunSync().swap.toOption.get shouldBe a[CommentFetchFailed]
    }

    it("should report the error message when a success response carries one") {
      val (client, _) = clientWithBody(None, """{"error": "no stored task for this article"}""")
      val error = client.getComments(commentUrl).attempt.unsafeRunSync().swap.toOption.get
      error.getMessage shouldBe "no stored task for this article"
    }

    it("should report the status text when an error response has no json body") {
      val backend = BackendStub(implicitly[MonadAsyncError[IO]]).whenRequestMatchesPartial { _ =>
        ResponseStub.adjust("Service Unavailable", StatusCode.ServiceUnavailable)
      }
      val error = new OpengardenClient(backend, None, UrlValidator.allowAll).getComments(commentUrl)
        .attempt.unsafeRunSync().swap.toOption.get
      error.getMessage shouldBe "Service Unavailable"
    }

    it("should reject a comment url that is not an http url") {
      val (client, requested) = clientWithBody(None)
      client.getComments("/comments?profile=p").attempt.unsafeRunSync().swap.toOption.get shouldBe
        a[CommentSourceNotSupported]
      requested() shouldBe None
    }

    it("should reject a comment url that points to a blocked address") {
      val (client, requested) = clientWithBody(None, validator = new UrlValidator(Seq.empty))
      client.getComments(commentUrl).attempt.unsafeRunSync().swap.toOption.get shouldBe a[SsrfBlockedError]
      requested() shouldBe None
    }

    it("should validate the rewritten url instead of the url from the feed") {
      val (client, requested) = clientWithBody(Some("http://opengarden:9020"),
        validator = new UrlValidator(Seq("opengarden:9020")))
      client.getComments(commentUrl).unsafeRunSync()
      checkRequested(requested().get, "opengarden", 9020)
    }

    it("should accept a blocked address that is an allowed internal host") {
      val (client, requested) = clientWithBody(None, validator = new UrlValidator(Seq("127.0.0.1:9020")))
      client.getComments(commentUrl).unsafeRunSync()
      checkRequested(requested().get, "127.0.0.1", 9020)
    }
  }

  describe("OpengardenClient article body") {

    it("should return the html body of the article as it is") {
      val (client, requested) = clientWithBody(None, response = articleBody)
      client.getArticleBody(fullContentUrl).unsafeRunSync() shouldBe articleBody
      checkArticleRequested(requested().get, "127.0.0.1", 9020)
    }

    it("should replace the host when a base url is configured") {
      val (client, requested) = clientWithBody(Some("http://opengarden:9020"), response = articleBody)
      client.getArticleBody(fullContentUrl).unsafeRunSync() shouldBe articleBody
      checkArticleRequested(requested().get, "opengarden", 9020)
    }

    it("should report the error message published by the api") {
      val backend = BackendStub(implicitly[MonadAsyncError[IO]]).whenRequestMatchesPartial { _ =>
        ResponseStub.adjust("""{"error": "the site is blocked"}""", StatusCode.ServiceUnavailable)
      }
      val error = new OpengardenClient(backend, None, UrlValidator.allowAll).getArticleBody(fullContentUrl)
        .attempt.unsafeRunSync().swap.toOption.get
      error shouldBe a[ArticleFullContentFetchFailed]
      error.getMessage shouldBe "the site is blocked"
    }

    it("should report the status text when an error response has no json body") {
      val backend = BackendStub(implicitly[MonadAsyncError[IO]]).whenRequestMatchesPartial { _ =>
        ResponseStub.adjust("Service Unavailable", StatusCode.ServiceUnavailable)
      }
      val error = new OpengardenClient(backend, None, UrlValidator.allowAll).getArticleBody(fullContentUrl)
        .attempt.unsafeRunSync().swap.toOption.get
      error shouldBe a[ArticleFullContentFetchFailed]
      error.getMessage shouldBe "Service Unavailable"
    }

    it("should reject an article url that is not an http url") {
      val (client, requested) = clientWithBody(None, response = articleBody)
      val error = client.getArticleBody("/articles?profile=p").attempt.unsafeRunSync().swap.toOption.get
      error shouldBe a[ArticleFullContentFetchFailed]
      error.getMessage shouldBe "Unsupported article url: /articles?profile=p"
      requested() shouldBe None
    }

    it("should reject an article url that points to a blocked address") {
      val (client, requested) = clientWithBody(None, response = articleBody, validator = new UrlValidator(Seq.empty))
      client.getArticleBody(fullContentUrl).attempt.unsafeRunSync().swap.toOption.get shouldBe a[SsrfBlockedError]
      requested() shouldBe None
    }

    it("should validate the rewritten url instead of the url from the feed") {
      val (client, requested) = clientWithBody(Some("http://opengarden:9020"), response = articleBody,
        validator = new UrlValidator(Seq("opengarden:9020")))
      client.getArticleBody(fullContentUrl).unsafeRunSync() shouldBe articleBody
      checkArticleRequested(requested().get, "opengarden", 9020)
    }

    it("should apply the read timeout to the request") {
      var readTimeouts: Seq[Duration] = Seq.empty
      val backend = BackendStub(implicitly[MonadAsyncError[IO]])
        .whenRequestMatches { request =>
          readTimeouts = readTimeouts :+ request.options.readTimeout
          true
        }
        .thenRespondAdjust(articleBody)
      val client = new OpengardenClient(backend, None, UrlValidator.allowAll, Some(5.seconds))
      client.getArticleBody(fullContentUrl).unsafeRunSync() shouldBe articleBody
      readTimeouts shouldBe Seq(5.seconds)
    }
  }

  private def checkRequested(requested: Uri, host: String, port: Int): Unit = {
    requested.host shouldBe Some(host)
    requested.port shouldBe Some(port)
    requested.path shouldBe Seq("comments")
    requested.params.toMap shouldBe Map(
      "profile" -> "p",
      "fetcher" -> "reddit",
      "article_url" -> articleUrl,
    )
  }

  private def checkArticleRequested(requested: Uri, host: String, port: Int): Unit = {
    requested.host shouldBe Some(host)
    requested.port shouldBe Some(port)
    requested.path shouldBe Seq("articles")
    requested.params.toMap shouldBe Map(
      "profile" -> "default",
      "fetcher" -> "reddit",
      "article_url" -> articleUrl,
    )
    requested.toString should include(s"article_url=$articleUrl")
  }

  private def clientWithBody(baseUrl: Option[String], response: String = body,
      validator: UrlValidator = UrlValidator.allowAll): (OpengardenClient, () => Option[Uri]) = {
    var requested: Option[Uri] = None
    val backend = BackendStub(implicitly[MonadAsyncError[IO]])
      .whenRequestMatches { request =>
        requested = Some(request.uri)
        true
      }
      .thenRespondAdjust(response)
    (new OpengardenClient(backend, baseUrl, validator), () => requested)
  }

}
