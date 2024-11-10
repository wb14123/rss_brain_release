package me.binwang.rss.webview.widgets

import me.binwang.rss.webview.basic.ContentRender.hxSwapContentAttrs
import me.binwang.rss.webview.basic.ScalaTagAttributes._
import scalatags.Text.all._

object EditSourceButton {

  private def folderMovingMenu(sourceID: String, folderID: String, text: Frag, func: String): Frag = {
    val checkLengthJs = "if (folders.length === 0) folders = [{name: 'No other folder', disabled: true}];"
    popoverMenu(
      PopoverMenu.subMenuAttrs,
      a(cls := "folder-move-menu-button", nullHref, text),
      xData := "{folders: []}",
      xOn("popover-opened") := s"folders = getFoldersFromDom('$folderID', true) ; $checkLengthJs",
      popoverContent(
        zIndex := "11",
        cls := "folder-select-menu",
        template(
          xFor := "f in folders",
          attr(":key") := "f.id",
          a(nullHref, xText := "f.name",
            xBind("class") := "f.disabled ? 'isDisabled' : ''",
            xOnClick := s"if (!f.disabled) {$func('$sourceID', '$folderID', f.id, f.nextPosition);} $$refs.folderEditMenu.closePopover();")
        )
      ),
    )
  }

  private def sourceMovingMenu(sourceID: String, folderID: String, text: Frag, func: String): Frag = {
    val checkLengthJs = "if (sources.length === 0) sources = [{name: 'No other feed', disabled: true}];"
    popoverMenu(
      PopoverMenu.subMenuAttrs,
      a(cls := "folder-move-menu-button", nullHref, text),
      xData := "{sources: []}",
      xOn("popover-opened") := s"sources = getSourcesFromFolder('$folderID', '$sourceID'); $checkLengthJs",
      popoverContent(
        zIndex := "11",
        cls := "folder-select-menu",
        template(
          xFor := "s in sources",
          attr(":key") := "s.id",
          a(nullHref, xText := "s.name",
            xBind("class") := "s.disabled ? 'isDisabled' : ''",
            xOnClick := s"if (!s.disabled) {$func('$folderID', '$sourceID', s.id);} $$refs.folderEditMenu.closePopover(); ")
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
          folderMovingMenu(sourceID, folderID, TextWithIcon("content_cut", "Move to folder ..."), "moveSourceToFolder"),
          folderMovingMenu(sourceID, folderID, TextWithIcon("content_copy", "Copy to folder ..."), "copySourceToFolder"),
          sourceMovingMenu(sourceID, folderID, TextWithIcon("north_west", "Move before feed ..."), "moveSourceBefore"),
          sourceMovingMenu(sourceID, folderID, TextWithIcon("south_west", "Move after feed ..."), "moveSourceAfter"),
          a(nullHref, TextWithIcon("settings", "Feed settings"),
            hxGet := s"/folders/$folderID/sources/$sourceID/edit",
            hxSwapContentAttrs, hxPushUrl := "true", xOnClick := "$refs.folderEditMenu.closePopover()"),
          a(nullHref, TextWithIcon("delete", "Delete from folder"),
            xOnClick := s"deleteSourceFromFolder('$sourceID', '$folderID') ; $$refs.folderEditMenu.closePopover()"),
          a(nullHref, TextWithIcon("delete_forever", "Unsubscribe"),
            xOnClick := s"unsubscribeSource('$sourceID'); $$refs.folderEditMenu.closePopover()"),
        )
      )
    }
  }

}
