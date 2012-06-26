/*
 * Copyright (C) 2011-2012 spray.cc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.spray.http

import java.nio.charset.Charset
import java.net.{URISyntaxException, URI}
import annotation.tailrec
import cc.spray.http.parser.{QueryParser, HttpParser}
import HttpHeaders._
import HttpCharsets._


sealed trait HttpMessagePart

sealed trait HttpRequestPart extends HttpMessagePart

sealed trait HttpResponsePart extends HttpMessagePart

sealed trait HttpMessageStart extends HttpMessagePart

sealed trait HttpMessageEnd extends HttpMessagePart

sealed abstract class HttpMessage extends HttpMessageStart with HttpMessageEnd {
  type Self <: HttpMessage

  def headers: List[HttpHeader]
  def entity: HttpEntity
  def protocol: HttpProtocol

  def parseHeaders: (List[String], Self) = {
    val (errors, parsed) = HttpParser.parseHeaders(headers)
    (errors, withHeaders(parsed))
  }

  def withHeaders(headers: List[HttpHeader]): Self
  def withEntity(entity: HttpEntity): Self
  def withHeadersAndEntity(headers: List[HttpHeader], entity: HttpEntity): Self

  def withHeadersTransformed(f: List[HttpHeader] => List[HttpHeader]): Self = {
    val transformed = f(headers)
    if (transformed eq headers) this.asInstanceOf[Self] else withHeaders(transformed)
  }
  def withEntityTransformed(f: HttpEntity => HttpEntity): Self = {
    val transformed = f(entity)
    if (transformed eq entity) this.asInstanceOf[Self] else withEntity(transformed)
  }

  /**
   * Returns true if a Content-Encoding header is present. 
   */
  def isEncodingSpecified: Boolean = headers.exists(_.isInstanceOf[`Content-Encoding`])

  /**
   * The content encoding as specified by the Content-Encoding header. If no Content-Encoding header is present the
   * default value 'identity' is returned.
   */
  def encoding = headers.collect { case `Content-Encoding`(enc) => enc } match {
    case enc :: _ => enc
    case Nil => HttpEncodings.identity
  }

  def header[T <: HttpHeader :ClassManifest]: Option[T] = {
    val erasure = classManifest[T].erasure
    @tailrec def next(headers: List[HttpHeader]): Option[T] =
      if (headers.isEmpty) None
      else if (erasure.isInstance(headers.head)) Some(headers.head.asInstanceOf[T]) else next(headers.tail)
    next(headers)
  }
}


/**
 * Sprays immutable model of an HTTP request.
 * The `uri` member contains the the undecoded URI of the request as it appears in the HTTP message,
 * i.e. just the path, query and fragment string without scheme and authority (host and port).
 */
final class HttpRequest private(
  val method: HttpMethod,
  val uri: String,
  val headers: List[HttpHeader],
  val entity: HttpEntity,
  val protocol: HttpProtocol,
  val URI: URI,
  val queryParams: Map[String, String]) extends HttpMessage with HttpRequestPart {

  type Self = HttpRequest

  def path     = if (URI.getPath     == null) "" else URI.getPath
  def query    = if (URI.getQuery    == null) "" else URI.getQuery
  def rawQuery = if (URI.getRawQuery == null) "" else URI.getRawQuery
  def fragment = if (URI.getFragment == null) "" else URI.getFragment

  /**
   * Parses the `uri` to create a copy of this request with the `URI` member updated or an error message
   * if the `uri` cannot be parsed.
   */
  def parseUri: Either[String, HttpRequest] = {
    try Right(copy(URI = new URI(uri)))
    catch {
      case e: URISyntaxException => Left("Illegal URI: " + e.getMessage)
    }
  }

  /**
   * Parses the query string to create a copy of this request with the `queryParams` member updated
   * or an error message if the query string cannot be parsed.
   * This method will implicitly call `parseUri` if this has not yet been done for this request.
   */
  def parseQuery: Either[String, HttpRequest] = {
    def doParseQuery(req: HttpRequest) =
      QueryParser.parseQueryString(req.rawQuery).right.map(params => req.copy(queryParams = params))
    if (URI eq HttpRequest.DefaultURI) parseUri.right.flatMap(doParseQuery)
    else doParseQuery(this)
  }

  def copy(method: HttpMethod = method, uri: String = uri, headers: List[HttpHeader] = headers,
           entity: HttpEntity = entity, protocol: HttpProtocol = protocol, URI: URI = URI,
           queryParams: Map[String, String] = queryParams): HttpRequest =
    new HttpRequest(method, uri, headers, entity, protocol, URI, queryParams)

  override def hashCode(): Int = (((((method.## * 31) + uri.##) * 31) + headers.##) * 31 + entity.##) + protocol.##
  override def equals(that: Any) = that match {
    case x: HttpRequest => (this eq x) ||
      method == x.method && uri == x.uri && headers == x.headers && entity == x.entity && protocol == x.protocol
    case _ => false
  }
  override def toString = "HttpRequest(%s, %s, %s, %s, %s)" format (method, uri, headers, entity, protocol)

  lazy val acceptedMediaRanges: List[MediaRange] = {
    // TODO: sort by preference
    for (Accept(mediaRanges) <- headers; range <- mediaRanges) yield range
  }

  lazy val acceptedCharsetRanges: List[HttpCharsetRange] = {
    // TODO: sort by preference
    for (`Accept-Charset`(charsetRanges) <- headers; range <- charsetRanges) yield range
  }

  lazy val acceptedEncodingRanges: List[HttpEncodingRange] = {
    // TODO: sort by preference
    for (`Accept-Encoding`(encodingRanges) <- headers; range <- encodingRanges) yield range
  }

  lazy val cookies: List[HttpCookie] = for (`Cookie`(cookies) <- headers; cookie <- cookies) yield cookie

  /**
   * Determines whether the given mediatype is accepted by the client.
   */
  def isMediaTypeAccepted(mediaType: MediaType) = {
    // according to the HTTP spec a client has to accept all mime types if no Accept header is sent with the request
    // http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.1
    acceptedMediaRanges.isEmpty || acceptedMediaRanges.exists(_.matches(mediaType))
  }

  /**
   * Determines whether the given charset is accepted by the client.
   */
  def isCharsetAccepted(charset: HttpCharset) = {
    // according to the HTTP spec a client has to accept all charsets if no Accept-Charset header is sent with the request
    // http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.2
    acceptedCharsetRanges.isEmpty || acceptedCharsetRanges.exists(_.matches(charset))
  }

  /**
   * Determines whether the given encoding is accepted by the client.
   */
  def isEncodingAccepted(encoding: HttpEncoding) = {
    // according to the HTTP spec the server MAY assume that the client will accept any content coding if no
    // Accept-Encoding header is sent with the request (http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.3)
    // this is what we do here
    acceptedEncodingRanges.isEmpty || encoding == HttpEncodings.identity ||
      acceptedEncodingRanges.exists(_.matches(encoding))
  }

  /**
   * Determines whether the given content-type is accepted by the client.
   */
  def isContentTypeAccepted(ct: ContentType) = {
    isMediaTypeAccepted(ct.mediaType) && (ct.noCharsetDefined || isCharsetAccepted(ct.definedCharset.get))
  }

  /**
   * Determines whether the given content-type is accepted by the client.
   * If the given content-type does not contain a charset an accepted charset is selected, i.e. the method guarantees
   * that, if a content-type instance is returned within the option it will contain a charset.
   */
  def acceptableContentType(contentType: ContentType): Option[ContentType] = {
    if (isContentTypeAccepted(contentType)) Some {
      if (contentType.isCharsetDefined) contentType
      else ContentType(contentType.mediaType, acceptedCharset)
    } else None
  }

  /**
   * Returns a charset that is accepted by the client.
   */
  def acceptedCharset: HttpCharset = {
    if (isCharsetAccepted(`ISO-8859-1`)) `ISO-8859-1`
    else acceptedCharsetRanges match {
      case (cs: HttpCharset) :: _ => cs
      case _ => throw new IllegalStateException // a HttpCharsetRange that is not `*` ?
    }
  }

  def withHeaders(headers: List[HttpHeader]) = copy(headers = headers)
  def withEntity(entity: HttpEntity) = copy(entity = entity)
  def withHeadersAndEntity(headers: List[HttpHeader], entity: HttpEntity) = copy(headers = headers, entity = entity)
}

object HttpRequest {
  val DefaultURI = new URI("")

  def apply(method: HttpMethod = HttpMethods.GET,
            uri: String = "/",
            headers: List[HttpHeader] = Nil,
            entity: HttpEntity = EmptyEntity,
            protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`): HttpRequest = {
    new HttpRequest(method, uri, headers, entity, protocol, DefaultURI, Map.empty)
  }

  def unapply(request: HttpRequest): Option[(HttpMethod, String, List[HttpHeader], HttpEntity, HttpProtocol)] = {
    import request._
    Some(method, uri, headers, entity, protocol)
  }
}


/**
 * Sprays immutable model of an HTTP response.
 */
case class HttpResponse(status: StatusCode = StatusCodes.OK,
                        headers: List[HttpHeader] = Nil,
                        entity: HttpEntity = EmptyEntity,
                        protocol: HttpProtocol = HttpProtocols.`HTTP/1.1`) extends HttpMessage with HttpResponsePart{
  type Self = HttpResponse

  def withHeaders(headers: List[HttpHeader]) = copy(headers = headers)
  def withEntity(entity: HttpEntity) = copy(entity = entity)
  def withHeadersAndEntity(headers: List[HttpHeader], entity: HttpEntity) = copy(headers = headers, entity = entity)
}

/**
 * Instance of this class represent the individual chunks of a chunked HTTP message (request or response).
 */
case class MessageChunk(body: Array[Byte], extensions: List[ChunkExtension]) extends HttpRequestPart with HttpResponsePart {
  require(body.length > 0, "MessageChunk must not have empty body")
  def bodyAsString: String = bodyAsString(HttpCharsets.`ISO-8859-1`.nioCharset)
  def bodyAsString(charset: HttpCharset): String = bodyAsString(charset.nioCharset)
  def bodyAsString(charset: Charset): String = if (body.isEmpty) "" else new String(body, charset)
  def bodyAsString(charset: String): String = if (body.isEmpty) "" else new String(body, charset)
}

object MessageChunk {
  import HttpCharsets._
  def apply(body: String): MessageChunk =
    apply(body, Nil)
  def apply(body: String, charset: HttpCharset): MessageChunk =
    apply(body, charset, Nil)
  def apply(body: String, extensions: List[ChunkExtension]): MessageChunk =
    apply(body, `ISO-8859-1`, extensions)
  def apply(body: String, charset: HttpCharset, extensions: List[ChunkExtension]): MessageChunk =
    apply(body.getBytes(charset.nioCharset), extensions)
  def apply(body: Array[Byte]): MessageChunk =
    apply(body, Nil)
}

case class ChunkedRequestStart(request: HttpRequest) extends HttpMessageStart with HttpRequestPart

case class ChunkedResponseStart(response: HttpResponse) extends HttpMessageStart with HttpResponsePart

case class ChunkedMessageEnd(
  extensions: List[ChunkExtension] = Nil,
  trailer: List[HttpHeader] = Nil
) extends HttpRequestPart with HttpResponsePart with HttpMessageEnd {
  if (!trailer.isEmpty) {
    require(trailer.forall(_.isNot("content-length")), "Content-Length header is not allowed in trailer")
    require(trailer.forall(_.isNot("transfer-encoding")), "Transfer-Encoding header is not allowed in trailer")
    require(trailer.forall(_.isNot("trailer")), "Trailer header is not allowed in trailer")
  }
}

case class ChunkExtension(name: String, value: String)