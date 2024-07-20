package me.binwang.rss.webview.basic

import scalatags.Text.all._
import scalatags.{Text, generic}
import scalatags.text.Builder

import java.time.ZonedDateTime

object ScalaTagAttributes {

  // basic
  private val nullJs = "javascript:void(0);"
  val nullHref: generic.AttrPair[Builder, String] = href := nullJs
  val nullClick: generic.AttrPair[Builder, String] = onclick := nullJs

  // basic attributes
  val is: Attr = attr("is")

  // htmx
  val hxTrigger: Attr = attr("hx-trigger")
  val hxClick: Attr = attr("hx-click")
  val hxTarget: Attr = attr("hx-target")
  val hxGet: Attr = attr("hx-get")
  val hxPost : Attr = attr("hx-post")
  val hxDelete : Attr = attr("hx-delete")
  val hxSwap: Attr = attr("hx-swap")
  val hxPushUrl: Attr = attr("hx-push-url")
  val hxIndicator: Attr = attr("hx-indicator")
  val hxSync: Attr = attr("hx-sync")
  val hxSelect: Attr = attr("hx-select")
  val hxHistoryElt: generic.AttrPair[Builder, String] = attr("hx-history-elt").empty
  val hxVals: Attr = attr("hx-vals")
  val hxExt: Attr = attr("hx-ext")
  val hxParams: Attr = attr("hx-params")
  val hxParamsAll: generic.AttrPair[Builder, String] = hxParams := "*"
  val hxInclude: Attr = attr("hx-include")
  val hxSwapOob: Attr = attr("hx-swap-oob")
  val hxDisableElt: Attr = attr("hx-disable-elt")
  val hxDisableThis: generic.AttrPair[Builder, String] = hxDisableElt := "this"
  val hxEncoding: Attr = attr("hx-encoding")

  // Alpine
  val xData: Attr = attr("x-data")
  val xInit: Attr = attr("x-init")
  val xText: Attr = attr("x-text")
  def xDate(time: ZonedDateTime): generic.AttrPair[Builder, String] =
    xText := s"new Date(${time.toEpochSecond * 1000L}).toLocaleString()"
  val xShow: Attr = attr("x-show")
  def xOn(event: String): Attr = attr(s"x-on:$event")
  val xOnClick: Attr = xOn("click")
  def xBind(name: String): Attr = attr(s"x-bind:$name")
  val xRef: Attr = attr("x-ref")
  val xFor: Attr = attr("x-for")
  val xIf: Attr = attr("x-if")
  val template: Text.TypedTag[String] = tag("template")

  // popover-menu
  val popoverMenu: Text.TypedTag[String] = tag("popover-menu")
  val popoverContent: Text.TypedTag[String] = tag("popover-content")
  val buttonSelector: Attr = attr("button-selector")
  val placement: Attr = attr("placement")
  val flip: generic.AttrPair[Builder, String] = attr("flip").empty
  val shift: generic.AttrPair[Builder, String] = attr("shift").empty
  val openEvent: Attr = attr("open-event")
  val openEventClick: generic.AttrPair[Builder, String] = openEvent := "click"
  val openEventMouseEnter : generic.AttrPair[Builder, String] = openEvent := "mouseenter"
  val closeEvent: Attr = attr("close-event")
  val contentDisplay: Attr = attr("content-display")

  // icons
  def iconSpan(name: String, iconCls: String = "material-icons-outlined"): Frag = span(cls :=iconCls)(name)

}
