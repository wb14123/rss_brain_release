package me.binwang.rss.dao

import cats.effect.IO
import me.binwang.rss.model.PaymentCustomer

trait PaymentCustomerDao {
  def createTable(): IO[Unit]
  def dropTable(): IO[Unit]
  def getByUserID(userID: String, thirdParty: String): IO[Option[PaymentCustomer]]
  def listByUserID(userID: String): fs2.Stream[IO, PaymentCustomer]
  def getByCustomerID(customerID: String, thirdParty: String): IO[Option[PaymentCustomer]]
  def insert(customer: PaymentCustomer): IO[Boolean]
}
