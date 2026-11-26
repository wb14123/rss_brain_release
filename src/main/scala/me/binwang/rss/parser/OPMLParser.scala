package me.binwang.rss.parser

import cats.effect.{IO, Resource}
import me.binwang.rss.model.{Folder, ID, Source}

import java.io.InputStream
import java.time.ZonedDateTime
import java.util.UUID
import scala.xml.{Elem, NodeSeq}

object OPMLParser {

  /**
   * A source parsed from the OPML file together with the name the file gives it.
   */
  case class ImportSource(
    source: Source,
    name: Option[String],
  )

  case class FolderWithSources(
    folder: Folder,
    sources: Seq[ImportSource],
  )

  /**
   * Parse the OPML xml with
   * @param content The input stream for the OPML file
   * @param userID Which user is parsing the file, it's used to set the folder's owner
   * @return A pair. The first part is all the sources that can fit into folders, the second part is all the other sources
   */
  def parse(content: Resource[IO, InputStream], userID: String, now: ZonedDateTime):
      IO[(Seq[FolderWithSources], Seq[ImportSource])] = {
    content.use { c =>
      IO(SecureXml.load(c) \ "body" \ "outline")
    }.map { outlines =>
      val parsed = outlines
        .zipWithIndex
        .map { case (outline, position) => parseOutline(outline, userID, (position + 1) * 1000, now)}
      val folders = parsed.flatMap { case (folderOpt, sources) =>
        folderOpt.map(folder => FolderWithSources(folder, sources.distinctBy(_.source.id)))
      }
      val sources = parsed.collect { case (None, sources) => sources }
        .flatten
        .distinctBy(_.source.id)
      (folders, sources)
    }
  }

  def generateXml(foldersWithSources: Seq[FolderWithSources], sources: Seq[ImportSource]): Elem = {
    val outlines = foldersWithSources.map { folderWithSource =>
      val folder = folderWithSource.folder
      <outline text={folder.name} title={folder.name}>
        {sourcesToXml(folderWithSource.sources)}
      </outline>
    }
    <opml version="1.0">
      <head>
        <title>Feeds exported from RSS Brain [https://www.rssbrain.com] </title>
      </head>
      <body>
        {sourcesToXml(sources)}
        {outlines}
      </body>
    </opml>
  }

  private def sourcesToXml(sources: Seq[ImportSource]): Seq[Elem] = {
    sources.map { importSource =>
      val title = importSource.name.getOrElse("")
      val xmlUrl = importSource.source.xmlUrl
      <outline text={title} title={title} type="rss" xmlUrl={xmlUrl}
               htmlUrl={importSource.source.htmlUrl.getOrElse(xmlUrl)}>
      </outline>
    }
  }

  /**
   * @return The folder declared by this outline, if any, and the sources it contains.
   */
  private def parseOutline(outline: NodeSeq, userID: String, position: Long, now: ZonedDateTime):
      (Option[Folder], Seq[ImportSource]) = {
    val children = outline \ "outline"
    if (children.isEmpty) {
      val xmlUrl = (outline \ "@xmlUrl").text.trim
      if (xmlUrl.isEmpty) {
        (None, Seq())
      } else {
        val source = Source(
          id = ID.hash(xmlUrl),
          xmlUrl = xmlUrl,
          importedAt = now,
          fetchScheduledAt = now,
          updatedAt = now,
        )
        (None, Seq(ImportSource(source, textAttr(outline, "text").orElse(textAttr(outline, "title")))))
      }
    } else {
      val sources = children
        .map(parseOutline(_, userID, position, now))
        .flatMap(_._2)
      val folder = textAttr(outline, "title").orElse(textAttr(outline, "text")).map { name =>
        Folder(
          id = UUID.randomUUID().toString,
          userID = userID,
          name = name,
          description = None,
          position = position,
          count = 0 // will increase while add sources to folder
        )
      }
      (folder, sources)
    }
  }

  private def textAttr(outline: NodeSeq, name: String): Option[String] = {
    val text = (outline \ s"@$name").text.trim
    Option.when(text.nonEmpty)(text)
  }

}
