package me.binwang.rss.webview.widgets

import me.binwang.rss.webview.basic.ContentRender.hxSwapContentAttrs
import me.binwang.rss.webview.basic.ScalaTagAttributes._
import scalatags.Text.all._

object EditFolderButton {

  private def moveFolderTargets(folderID: String, getPositionFunc: String): Frag = {
    template(
      xFor := "f in folders",
      attr(":key") := "f.id",
      a(nullHref, xText := "f.name",
        xOnClick := s"updateFolderPosition('$folderID', await $getPositionFunc(f.id)); $$refs.folderEditMenu.closePopover();")
    )
  }

  private def folderMovingMenu(folderID: String, text: String, getPositionJSFunc: String): Frag = {
    popoverMenu(
      PopoverMenu.subMenuAttrs,
      a(cls := "folder-move-menu-button", nullHref, text),
      xData := "{folders: []}",
      xOn("popover-opened") := s"folders = getFoldersFromDom('$folderID', false)",
      popoverContent(
        zIndex := "11",
        cls := "folder-select-menu",
        moveFolderTargets(folderID, getPositionJSFunc)
      ),
    )
  }

  def apply(folderID: String): Frag = {
    popoverMenu(
      PopoverMenu.menuAttrs,
      a(cls := "folder-op-button", nullHref, title := "Folder operations", iconSpan("more_horiz")),
      popoverContent(
        cls := "folder-op-menu",
        zIndex := "10",
        folderMovingMenu(folderID, "Move after folder ...", "getPositionAfter"),
        folderMovingMenu(folderID, "Move before folder ...", "getPositionBefore"),
        a(nullHref, "Edit folder", hxGet := s"/folders/$folderID/edit", hxSwapContentAttrs, hxPushUrl := "true",
          xOnClick := "$refs.folderEditMenu.closePopover()"),
        a(nullHref, "Delete folder", hxDelete := s"/hx/folders/$folderID",
          xOnClick := "$refs.folderEditMenu.closePopover()"),
      )
    )

  }

}
