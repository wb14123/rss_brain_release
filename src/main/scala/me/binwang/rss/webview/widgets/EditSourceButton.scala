package me.binwang.rss.webview.widgets

import me.binwang.rss.webview.basic.ContentRender.hxSwapContentAttrs
import me.binwang.rss.webview.basic.ScalaTagAttributes._
import scalatags.Text.all._

object EditSourceButton {

  private def folderMovingMenu(sourceID: String, folderID: String, text: String, func: String): Frag = {
    popoverMenu(
      PopoverMenu.subMenuAttrs,
      a(cls := "folder-move-menu-button", nullHref, text),
      xData := "{folders: []}",
      xOn("popover-opened") := s"folders = getFoldersFromDom('$folderID', true)",
      popoverContent(
        zIndex := "11",
        cls := "folder-select-menu",
        template(
          xFor := "f in folders",
          attr(":key") := "f.id",
          a(nullHref, xText := "f.name",
            xOnClick := s"$func('$sourceID', '$folderID', f.id, f.nextPosition); $$refs.folderEditMenu.closePopover();")
        )
      ),
    )
  }

  private def sourceMovingMenu(sourceID: String, folderID: String, text: String, func: String): Frag = {
    popoverMenu(
      PopoverMenu.subMenuAttrs,
      a(cls := "folder-move-menu-button", nullHref, text),
      xData := "{sources: []}",
      xOn("popover-opened") := s"sources = getSourcesFromFolder('$folderID', '$sourceID')",
      popoverContent(
        zIndex := "11",
        cls := "folder-select-menu",
        template(
          xFor := "s in sources",
          attr(":key") := "s.id",
          a(nullHref, xText := "s.name",
            xOnClick := s"$func('$folderID', '$sourceID', s.id) ; $$refs.folderEditMenu.closePopover(); ")
        )
      )
    )
  }

  def apply(sourceID: String, folderIDOpt: Option[String]): Option[Frag] = {
    folderIDOpt.map { folderID =>
      popoverMenu(
        PopoverMenu.menuAttrs,
        a(cls := "folder-op-button", nullHref, title := "Feed operations", iconSpan("more_horiz")),
        popoverContent(
          cls := "folder-op-menu",
          zIndex := "10",
          folderMovingMenu(sourceID, folderID, "Move to folder ...", "moveSourceToFolder"),
          folderMovingMenu(sourceID, folderID, "Copy to folder ...", "copySourceToFolder"),
          sourceMovingMenu(sourceID, folderID, "Move before feed ...", "moveSourceBefore"),
          sourceMovingMenu(sourceID, folderID, "Move after feed ...", "moveSourceAfter"),
          a(nullHref, "Edit feed", hxGet := s"/folders/$folderID/sources/$sourceID/edit", hxSwapContentAttrs, hxPushUrl := "true",
            xOnClick := "$refs.folderEditMenu.closePopover()"),
          a(nullHref, "Delete from folder",
            xOnClick := s"deleteSourceFromFolder('$sourceID', '$folderID') ; $$refs.folderEditMenu.closePopover()"),
          a(nullHref, "Unsubscribe", xOnClick := s"unsubscribeSource('$sourceID'); $$refs.folderEditMenu.closePopover()"),
        )
      )
    }
  }

}
