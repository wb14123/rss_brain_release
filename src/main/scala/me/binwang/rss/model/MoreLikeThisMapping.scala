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

/**
 * Settings for more like this articles. Server is mostly just store the configs. Clients need to request different
 * APIs to get more like this articles. An article can have multiple sections for more like this articles. The sorting
 * depends on `position`.
 *
 * @param fromID Can be source ID or folder ID depends on `fromType`. It means the config is for this source or foder.
 * @param fromType Can be folder or source.
 * @param moreLikeThisID Where to find more like this articles. Can be a source ID, folder ID or empty if search
 *                       articles from all sources. The meaning of this ID depends on `moreLikeThisType`.
 * @param moreLikeThisType Can be folder, source or all articles.
 * @param userID Which user this setting is associated to.
 * @param position The position to show the more like this section.
 */
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
