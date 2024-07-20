package me.binwang.rss.dao.sql

import cats.effect.IO
import doobie._
import doobie.implicits._
import me.binwang.rss.dao.PaymentCustomerDao
import me.binwang.rss.model.PaymentCustomer

class PaymentCustomerSqlDao(implicit val connectionPool: ConnectionPool) extends PaymentCustomerDao with BaseSqlDao  {

  import dbCtx._

  override def table: String = "paymentCustomer"

  override def createTable(): IO[Unit] = {

    Fragment.const(
      s"""create table if not exists $table (
            userID char($UUID_LENGTH),
            thirdParty varchar not null,
            customerID varchar not null,
            createdAt timestamp not null,
            PRIMARY KEY (userID, thirdParty)
          )
         """)
      .update
      .run
      .flatMap(_ => createIndexWithFields(Seq(("customerID", false), ("thirdParty", false)), unique = true))
      .flatMap(_ => createIndex("userID"))
      .transact(xa)
      .map(_ => ())
  }

  override def getByUserID(userID: String, thirdParty: String): IO[Option[PaymentCustomer]] = {
    run(quote {
      query[PaymentCustomer]
        .filter(_.userID == lift(userID))
        .filter(_.thirdParty == lift(thirdParty))
        .take(1)
    }).transact(xa).map(_.headOption)
  }

  override def listByUserID(userID: String): fs2.Stream[IO, PaymentCustomer] = {
    stream(quote {
      query[PaymentCustomer]
        .filter(_.userID == lift(userID))
    }).transact(xa)
  }

  override def insert(customer: PaymentCustomer): IO[Boolean] = {
    run(quote {
      query[PaymentCustomer].insertValue(lift(customer)).onConflictIgnore
    }).transact(xa).map(_ > 0)
  }

  override def getByCustomerID(customerID: String, thirdParty: String): IO[Option[PaymentCustomer]] = {
    run(quote {
      query[PaymentCustomer]
        .filter(_.customerID == lift(customerID))
        .filter(_.thirdParty == lift(thirdParty))
        .take(1)
    }).transact(xa).map(_.headOption)
  }
}
