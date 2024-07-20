package me.binwang.rss.model

import me.binwang.rss.model.MoreLikeThisType.MoreLikeThisType

object MoreLikeThisType extends Enumeration {
  type MoreLikeThisType = Value
  val
  FOLDER,
  SOURCE,
  ALL
  = Value
}

case class MoreLikeThisMapping(
    fromID: String,
    fromType: MoreLikeThisType,
    moreLikeThisID: String,
    moreLikeThisType: MoreLikeThisType,
    userID: String,
    position: Long,
) {
  // moreLikeThisID must be "" if the type is ALL
  if (moreLikeThisType.equals(MoreLikeThisType.ALL)) {
    assert(moreLikeThisID.equals(""))
  }
}
