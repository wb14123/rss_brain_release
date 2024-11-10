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
        xBind("class") := "f.disabled ? 'isDisabled' : ''",
        xOnClick := s"if (!f.disabled) {updateFolderPosition('$folderID', await $getPositionFunc(f.id));} $$refs.folderEditMenu.closePopover();")
    )
  }

  private def folderMovingMenu(folderID: String, text: Frag, getPositionJSFunc: String): Frag = {
    val checkLengthJs = "if (folders.length === 0) folders = [{name: 'No other folder', disabled: true}];"
    popoverMenu(
      PopoverMenu.subMenuAttrs,
      a(cls := "folder-move-menu-button", nullHref, text),
      xData := "{folders: []}",
      xOn("popover-opened") := s"folders = getFoldersFromDom('$folderID', false) ; $checkLengthJs",
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
        folderMovingMenu(folderID, TextWithIcon("north_west", "Move before folder ..."), "getPositionBefore"),
        folderMovingMenu(folderID, TextWithIcon("south_west", "Move after folder ..."), "getPositionAfter"),
        a(nullHref, TextWithIcon("settings", "Folder settings"),
          hxGet := s"/folders/$folderID/edit", hxSwapContentAttrs, hxPushUrl := "true",
          xOnClick := "$refs.folderEditMenu.closePopover()"),
        a(nullHref, TextWithIcon("delete", "Delete folder"), hxDelete := s"/hx/folders/$folderID",
          xOnClick := "$refs.folderEditMenu.closePopover()"),
        a(nullHref, TextWithIcon("public", "Recommendations"),
          hxGet := s"/folders/$folderID/external-recommend", hxPushUrl := "true", hxSwapContentAttrs)
      )
    )

  }

}
