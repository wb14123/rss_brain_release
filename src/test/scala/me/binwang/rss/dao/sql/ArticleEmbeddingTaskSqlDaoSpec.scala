package me.binwang.rss.dao.sql

import cats.effect.unsafe.IORuntime
import me.binwang.rss.generator.Articles
import me.binwang.rss.generator.ConnectionPoolManager.connectionPool
import me.binwang.rss.model.{ArticleEmbeddingTask, ArticleEmbeddingTaskUpdater, EmbeddingUpdateStatus}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import java.time.ZonedDateTime

class ArticleEmbeddingTaskSqlDaoSpec extends AnyFunSpec with BeforeAndAfterEach with BeforeAndAfterAll with Matchers{


  implicit val ioRuntime: IORuntime = IORuntime.global

  private val articleEmbeddingTaskDao = new ArticleEmbeddingTaskSqlDao()


  override def beforeAll(): Unit = {
    articleEmbeddingTaskDao.dropTable().unsafeRunSync()
    articleEmbeddingTaskDao.createTable().unsafeRunSync()
  }

  override def beforeEach(): Unit = {
    articleEmbeddingTaskDao.deleteAll().unsafeRunSync()
  }

  describe("Article embedding task sql dao") {

    it("should schedule task if not exists") {
      val articleID = Articles.get().id
      val title = "random title"
      articleEmbeddingTaskDao.schedule(ArticleEmbeddingTask(
        articleID = articleID,
        title = title,
        scheduledAt = ZonedDateTime.now()
      )).unsafeRunSync()
      var tasks = articleEmbeddingTaskDao.getTasksForUpdate(ZonedDateTime.now(), 10).compile.toList.unsafeRunSync()
      tasks.size shouldBe 1
      tasks.head.articleID shouldBe articleID
      tasks.head.title shouldBe title
      tasks.head.status shouldBe EmbeddingUpdateStatus.PENDING

      val task = articleEmbeddingTaskDao.get(articleID).unsafeRunSync().get
      task.status shouldBe EmbeddingUpdateStatus.UPDATING
      task.startedAt.isEmpty shouldBe false

      tasks = articleEmbeddingTaskDao.getTasksForUpdate(ZonedDateTime.now(), 10).compile.toList.unsafeRunSync()
      tasks.size shouldBe 0
    }

    it("should reschedule task if exists") {
      val articleID = Articles.get().id
      val title = "random title"

      articleEmbeddingTaskDao.schedule(ArticleEmbeddingTask(
        articleID = articleID,
        title = title,
        scheduledAt = ZonedDateTime.now()
      )).unsafeRunSync()

      var tasks = articleEmbeddingTaskDao.getTasksForUpdate(ZonedDateTime.now(), 10).compile.toList.unsafeRunSync()
      tasks.size shouldBe 1

      articleEmbeddingTaskDao.schedule(ArticleEmbeddingTask(
        articleID = articleID,
        title = "new title",
        scheduledAt = ZonedDateTime.now()
      )).unsafeRunSync()

      val task = articleEmbeddingTaskDao.get(articleID).unsafeRunSync().get
      task.status shouldBe EmbeddingUpdateStatus.PENDING

      tasks = articleEmbeddingTaskDao.getTasksForUpdate(ZonedDateTime.now(), 10).compile.toList.unsafeRunSync()
      tasks.size shouldBe 1
      tasks.head.title shouldBe "new title"
    }

    it("should not reschedule task if title is the same") {
      val articleID = Articles.get().id
      val title = "random title"

      articleEmbeddingTaskDao.schedule(ArticleEmbeddingTask(
        articleID = articleID,
        title = title,
        scheduledAt = ZonedDateTime.now()
      )).unsafeRunSync()

      var tasks = articleEmbeddingTaskDao.getTasksForUpdate(ZonedDateTime.now(), 10).compile.toList.unsafeRunSync()
      tasks.size shouldBe 1

      articleEmbeddingTaskDao.schedule(ArticleEmbeddingTask(
        articleID = articleID,
        title = title,
        scheduledAt = ZonedDateTime.now()
      )).unsafeRunSync()


      val task = articleEmbeddingTaskDao.get(articleID).unsafeRunSync().get
      task.status shouldBe EmbeddingUpdateStatus.UPDATING

      tasks = articleEmbeddingTaskDao.getTasksForUpdate(ZonedDateTime.now(), 10).compile.toList.unsafeRunSync()
      tasks.size shouldBe 0
    }

    it("should update task") {
      val articleID = Articles.get().id
      val title = "random title"

      articleEmbeddingTaskDao.schedule(ArticleEmbeddingTask(
        articleID = articleID,
        title = title,
        scheduledAt = ZonedDateTime.now()
      )).unsafeRunSync()

      articleEmbeddingTaskDao.update(articleID,
        ArticleEmbeddingTaskUpdater(status = Some(EmbeddingUpdateStatus.FINISHED))).unsafeRunSync()

      val task = articleEmbeddingTaskDao.get(articleID).unsafeRunSync().get
      task.status shouldBe EmbeddingUpdateStatus.FINISHED
    }

    it("should delete finished tasks") {
      val articleID = Articles.get().id
      val title = "random title"

      articleEmbeddingTaskDao.schedule(ArticleEmbeddingTask(
        articleID = articleID,
        title = title,
        scheduledAt = ZonedDateTime.now()
      )).unsafeRunSync()

      articleEmbeddingTaskDao.update(articleID,
        ArticleEmbeddingTaskUpdater(
          status = Some(EmbeddingUpdateStatus.FINISHED),
          finishedAt = Some(Some(ZonedDateTime.now().minusHours(1)))))
        .unsafeRunSync()

      articleEmbeddingTaskDao.deleteFinishedTasks(ZonedDateTime.now()).unsafeRunSync()

      articleEmbeddingTaskDao.get(articleID).unsafeRunSync() shouldBe None
    }

    it("should reschedule timeout tasks") {

      val articleID = Articles.get().id
      val title = "random title"

      articleEmbeddingTaskDao.schedule(ArticleEmbeddingTask(
        articleID = articleID,
        title = title,
        scheduledAt = ZonedDateTime.now()
      )).unsafeRunSync()

      articleEmbeddingTaskDao.update(articleID,
        ArticleEmbeddingTaskUpdater(
          status = Some(EmbeddingUpdateStatus.UPDATING),
          startedAt = Some(Some(ZonedDateTime.now().minusHours(1)))))
        .unsafeRunSync()

      val now = ZonedDateTime.now()
      articleEmbeddingTaskDao.rescheduleTasks(now, now).unsafeRunSync()

      val task = articleEmbeddingTaskDao.get(articleID).unsafeRunSync().get
      task.status shouldBe EmbeddingUpdateStatus.PENDING
      task.startedAt shouldBe None
    }

  }

}
