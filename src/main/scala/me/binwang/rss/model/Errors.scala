package me.binwang.rss.model

import java.time.ZonedDateTime

class ServerException(code: Int, msg: String) extends Throwable(msg)

// Authorization
final case class UserNotAuthorized(token: String)
  extends ServerException(code = 10001, msg = s"Invalid user token $token")

final case class NoPermissionOnFolder(userID: String, folderID: String)
  extends ServerException(code = 10002, msg = s"No read permission for user $userID on folder $folderID")

final case class UserIsNotAdmin(userID: String)
  extends ServerException(code = 10003, msg = s"User $userID is not admin")

final case class UserSubscriptionEnded(userID: String, subscribeEndTime: ZonedDateTime)
  extends ServerException(code = 10004, msg = s"Subscription for $userID ended at $subscribeEndTime")

final case class NoPermissionOnFolders(userID: String, folderIDs: Seq[String])
  extends ServerException(code = 10005, msg = s"No read permission for user $userID on folders $folderIDs")

final case class RateLimitExceedError(key: String, typ: String, limitPerMinute: Long)
  extends ServerException(code = 10006, msg = s"Rate limit exceed for key $key with type $typ, limit per minute: $limitPerMinute")

// User service

final case class EmailAlreadyUsed(email: String)
  extends ServerException(code = 20001, msg = s"Email $email already used by other user")

final case class LoginFail(email: String)
  extends ServerException(code = 20002, msg = s"User does not exist or incorrect password, user email: $email")

final case class UserNotFound(userID: String)
  extends ServerException(code = 20003, msg = s"User not found: $userID")

final case class RedditSessionNotFoundException(userID: String)
  extends ServerException(code = 20004, msg = s"Reddit session not found for user $userID")

final case class PasswordResetInvalidException(token: String)
  extends ServerException(code = 20005, msg = s"Invalid token to reset password, token: $token")

final case class UserNotActivated(email: String)
  extends ServerException(code = 20006, msg = s"User $email is not activated")

final case class InvalidActiveCodeException(code: String)
  extends ServerException(code = 20007, msg = s"Active code $code is not valid")

final case class UserAlreadyActivated(email: String)
  extends ServerException(code = 20008, msg = s"User $email is already activated")

final case class UserCannotBeActivated(email: String)
  extends ServerException(code = 20008, msg = s"User $email cannot be activated")

final case class UserDeleteCodeInvalidException(code: String)
  extends ServerException(code = 20009, msg = s"User delete verification code $code is not valid")

// Source service

final case class SourceNotFound(sourceID: String)
  extends ServerException(code = 30001, msg = s"Source not found: $sourceID")

final case class SourceAlreadyInFolder(sourceID: String, folderID: String)
  extends ServerException(code = 30002, msg = s"Source $sourceID already in folder $folderID")

final case class SourceInFolderNotFoundError(sourceID: String, folderID: String)
  extends ServerException(code = 30003, msg = s"Source $sourceID in folder $folderID not found")

final case class SourcePositionCleanupFailed(folderID: String, retried: Int)
  extends ServerException(code = 30004, msg = s"Failed to cleanup source position in folder $folderID after $retried retries")

// Fetcher

final case class FetchSourceError(url: String, err: Throwable)
  extends ServerException(code = 40001, msg = s"Error to fetch source $url")

final case class ParseArticleError(url: String, errs: Seq[Throwable])
  extends ServerException(code = 40002, msg = s"Error to parse articles in $url: ${errs.map(_.getMessage).mkString(", ")}")

final case class ArticleIDConflictError(article1: Article, article2: Article)
  extends ServerException(code = 40003, msg = s"Article ID hash conflict. " +
    s"Article 1 source id: ${article1.sourceID}, guid: ${article1.guid}. " +
    s"Article 2 source id: ${article2.sourceID}, guid: ${article2.guid}. ")

final case class SourceIDConflictError(source1: Source, source2: Source)
  extends ServerException(code = 40004, msg = s"Source ID hash conflict. " +
    s"Source1 xml url: ${source1.xmlUrl}. Source2 xml url: ${source2.xmlUrl}")

// Article service
final case class ArticleNotFound(articleID: String)
  extends ServerException(code = 50001, msg = s"Article $articleID not found")

// Folder service
final case class FolderDuplicateError(folderID: String, folderName: String)
  extends ServerException(code = 60001, msg = s"""Folder with name \"$folderName\" already exists""")

final case class RedditAPIException(code: Int, msg: String) extends ServerException(code = 70001, msg = msg)

// Mail service
final case class SendMailException(msg: String, causeOpt: Option[Throwable] = None)
    extends ServerException(code = 80001, msg = msg) {
  if (causeOpt.isDefined) {
    initCause(causeOpt.get)
  }
}

// Payment service
final case class PaymentRecordNotFound(id: String, typ: String) extends ServerException(code = 90001,
  msg = s"Payment record not found, id: $id, type: $typ")

final case class PaymentAlreadyProceed(id: String, typ: String) extends ServerException(code = 90002,
  msg = s"Payment already proceed, id: $id, type: $typ")

final case class PaymentCustomerNotFound(userID: String) extends ServerException(code = 90003,
  msg = s"Payment customer not found for user $userID")

final case class SubscriptionExpireDateTooEarly(userID: String, customerID: String, thirdParty: String,
    expireDate: ZonedDateTime, userSubscriptionExpireDate: ZonedDateTime) extends ServerException(code = 90004,
  msg = s"Subscription expire date $expireDate is earlier than user's current expire data $userSubscriptionExpireDate, " +
  s"userID: $userID, customerID: $customerID, thirdParty: $thirdParty")


