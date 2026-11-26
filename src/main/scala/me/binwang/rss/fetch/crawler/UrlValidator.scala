package me.binwang.rss.fetch.crawler

import cats.effect.IO
import me.binwang.rss.model.SsrfBlockedError

import java.net.{InetAddress, URI}

class UrlValidator(allowedHosts: Seq[String] = Seq.empty) {

  private val allowedPorts: Map[String, Set[Int]] = parseAllowedHosts(allowedHosts)

  def validate(url: String): IO[Unit] = {
    for {
      uri  <- IO.fromEither(parseUri(url))
      _    <- checkScheme(uri)
      port <- IO.fromEither(effectivePort(uri, url))
      _    <- checkHost(uri.getHost, port, url)
    } yield ()
  }

  private def parseUri(url: String): Either[SsrfBlockedError, URI] = {
    try {
      val uri = new URI(url)
      if (uri.getHost == null) Left(SsrfBlockedError(url, "missing host"))
      else Right(uri)
    } catch {
      case _: Exception => Left(SsrfBlockedError(url, "malformed URL"))
    }
  }

  private def checkScheme(uri: URI): IO[Unit] = {
    val scheme = Option(uri.getScheme).map(_.toLowerCase)
    scheme match {
      case Some("http") | Some("https") => IO.unit
      case _ => IO.raiseError(SsrfBlockedError(uri.toString, s"disallowed scheme: ${scheme.getOrElse("none")}"))
    }
  }

  private def effectivePort(uri: URI, url: String): Either[SsrfBlockedError, Int] = {
    uri.getPort match {
      case -1 => Right(defaultPort(uri.getScheme))
      case port if port >= 1 && port <= 65535 => Right(port)
      case port => Left(SsrfBlockedError(url, s"invalid port: $port"))
    }
  }

  private def defaultPort(scheme: String): Int =
    if (scheme.equalsIgnoreCase("https")) 443 else 80

  private def checkHost(rawHost: String, port: Int, url: String): IO[Unit] = {
    val host = normalizeHost(rawHost)
    allowedPorts.get(host) match {
      case Some(ports) if ports.contains(port) => IO.unit
      case Some(ports) =>
        val allowed = ports.toSeq.sorted.mkString(", ")
        val reason = s"port $port not allowed for host $host (allowed: $allowed)"
        IO.raiseError(SsrfBlockedError(url, reason))
      case None => checkAddresses(rawHost, url)
    }
  }

  private def checkAddresses(host: String, originalUrl: String): IO[Unit] = {
    IO.blocking(InetAddress.getAllByName(host)).flatMap { addresses =>
      val blocked = addresses.find(isPrivateAddress)
      blocked match {
        case Some(addr) =>
          IO.raiseError(SsrfBlockedError(originalUrl, s"resolves to private address ${addr.getHostAddress}"))
        case None => IO.unit
      }
    }
  }

  private def isPrivateAddress(addr: InetAddress): Boolean =
    addr.isLoopbackAddress ||
      addr.isSiteLocalAddress ||
      addr.isLinkLocalAddress ||
      addr.isAnyLocalAddress ||
      addr.isMulticastAddress

  private def parseAllowedHosts(entries: Seq[String]): Map[String, Set[Int]] =
    entries.map(parseAllowedHost).groupMapReduce(_._1)(entry => Set(entry._2))(_ ++ _)

  private def parseAllowedHost(entry: String): (String, Int) = {
    val parts = entry.split(":", -1)
    if (parts.length != 2 || entry.exists(c => c.isWhitespace || c == '*')) invalidEntry(entry)
    val host = parts(0)
    if (host.isEmpty) invalidEntry(entry)
    val port = parts(1).toIntOption.filter(p => p >= 1 && p <= 65535).getOrElse(invalidEntry(entry))
    (normalizeHost(host), port)
  }

  private def normalizeHost(host: String): String = {
    val lower = host.toLowerCase
    if (lower.endsWith(".")) lower.dropRight(1) else lower
  }

  private def invalidEntry(entry: String): Nothing =
    throw new IllegalArgumentException(s"invalid allowed host entry '$entry', expected host:port")
}

object UrlValidator {
  val allowAll: UrlValidator = new UrlValidator() {
    override def validate(url: String): IO[Unit] = IO.unit
  }
}
