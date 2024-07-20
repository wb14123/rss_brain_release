package me.binwang.rss.parser

import cats.effect.{IO, Resource}
import me.binwang.rss.model.{Folder, ID, Source}

import java.io.InputStream
import java.time.ZonedDateTime
import java.util.UUID
import scala.xml.{Elem, NodeSeq, XML}

object OPMLParser {

  case class FolderWithSources(
    folder: Folder,
    sources: Seq[Source]
  )

  /**
   * Parse the OPML xml with
   * @param content The input stream for the OPML file
   * @param userID Which user is parsing the file, it's used to set the folder's owner
   * @return A pair. The first part is all the sources that can fit into folders, the second part is all the other sources
   */
  def parse(content: Resource[IO, InputStream], userID: String, now: ZonedDateTime): IO[(Seq[FolderWithSources], Seq[Source])] = {
    content.use { c =>
      IO(XML.load(c) \ "body" \ "outline")
    }.map{ outlines =>
      val results = outlines
        .zipWithIndex
        .map { case (outline, position) => parseOutline(outline, userID, (position + 1) * 1000, now)}
      val folders = results.flatMap {
        case Left(_) => None
        case Right(folder) => Some(folder)
      }
      val sources = results.flatMap {
        case Left(source) => Some(source)
        case Right(_) => None
      }
      (folders, sources)
    }
  }

  def generateXml(foldersWithSources: Seq[FolderWithSources], sources: Seq[Source]): Elem = {
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

  private def sourcesToXml(sources: Seq[Source]): Seq[Elem] = {
    sources.map { source =>
      val title = source.title.getOrElse("")
      <outline text={title} title={title} type="rss" xmlUrl={source.xmlUrl} htmlUrl={source.htmlUrl.getOrElse(source.xmlUrl)}>
      </outline>
    }
  }

  private def parseOutline(outline: NodeSeq, userID: String, position: Long, now: ZonedDateTime): Either[Source, FolderWithSources] = {
    val children = outline \ "outline"
    if (children.isEmpty) {
      val xmlUrl = (outline \ "@xmlUrl").text
      Left(Source(
        id = ID.hash(xmlUrl),
        xmlUrl = xmlUrl,
        importedAt = now,
        fetchScheduledAt = now,
        updatedAt = now,
      ))
    } else {
      val folder = Folder(
        id = UUID.randomUUID().toString,
        userID = userID,
        name = (outline \ "@title").text,
        description = None,
        position = position,
        count = 0 // will increase while add sources to folder
      )
      val sources = children
        .map(parseOutline(_, userID, position, now))
        .flatMap {
          case Left(source) => Some(source)
          case Right(_) => None
        }
      Right(FolderWithSources(folder, sources))
    }
  }

}
