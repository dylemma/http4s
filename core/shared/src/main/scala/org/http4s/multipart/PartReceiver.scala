package org.http4s.multipart

import cats.effect.kernel.Resource
import cats.{Applicative, ApplicativeError, MonadThrow}
import fs2.io.file.{Files, Flags, Path}
import fs2.{Compiler, Pipe, Pull, Pure, RaiseThrowable, Stream}
import org.http4s.{DecodeFailure, EntityDecoder, Headers, InvalidMessageBodyFailure}

/** Represents the decoding process of a single "part" in a `multipart/form-data` message.
  *
  * Used in conjunction with [[MultipartReceiver]] and [[MultipartDecoder.fromReceiver]]
  * to create robust [[EntityDecoder]]s with fail-fast behavior.
  */
trait PartReceiver[F[_], A] {
  def receive(part: Part[F]): Resource[F, Either[DecodeFailure, A]]

  def map[B](f: A => B): PartReceiver[F, B] =
    part => this.receive(part).map(_.map(f))

  def mapWithHeaders[B](f: (Headers, A) => B): PartReceiver[F, B] =
    part => this.receive(part).map(_.map(f(part.headers, _)))

  def tapStart(onStart: F[Unit]): PartReceiver[F, A] =
    part => Resource.eval(onStart).flatMap(_ => this.receive(part))

  def tapResult(onResult: Either[DecodeFailure, A] => F[Unit]): PartReceiver[F, A] =
    part => this.receive(part).evalTap(onResult)

  def tapRelease(onRelease: F[Unit])(implicit F: Applicative[F]): PartReceiver[F, A] =
    part => Resource.make(F.unit)(_ => onRelease).flatMap(_ => this.receive(part))

  def preprocess(transformPartBody: Pipe[F, Byte, Byte]): PartReceiver[F, A] =
    part => this.receive(part.copy(body = transformPartBody(part.body)))

  def withSizeLimit(limit: Long)(implicit F: ApplicativeError[F, Throwable]): PartReceiver[F, A] =
    preprocess(PartReceiver.limitPartSize[F](limit))


}

object PartReceiver {

  /** Creates a PartReceiver which decodes the part body to a String.
    *
    * The decoding will use UTF-8 unless the part provides a `Content-Type` header indicating otherwise.
    */
  def bodyText[F[_]](implicit F: RaiseThrowable[F], cmp: Compiler[F, F]): PartReceiver[F, String] =
    part => Resource.eval(part.bodyText.compile.string).map(Right(_))

  /** Creates a PartReceiver which writes the part body to a temporary file, then returns that file's `Path`. */
  def toTempFile[F[_]](implicit F: Files[F], c: Compiler[F, F]): PartReceiver[F, Path] =
    part =>
      F.tempFile
        .evalTap(path => part.body.through(F.writeAll(path)).compile.drain)
        .map(Right(_))

  /** Creates a PartReceiver that ignores the part body. */
  def ignore[F[_]]: PartReceiver[F, Unit] =
    _ => Resource.pure(Right(()))

  /** Creates a PartReceiver that immediately returns the given `DecodeFailure` instead of consuming the part body.
    *
    * @param err The error returned by the created PartReceiver
    */
  def reject[F[_], A](err: DecodeFailure): PartReceiver[F, A] =
    _ => Resource.pure(Left(err))

  /** Creates a PartReceiver that delegates to an implicitly-resolved `EntityDecoder` to decode the part body,
    * passing `strict = false` to the underlying `decode` method.
    *
    * This method passes `strict = false` to the `decoder.decode` method.
    *
    * @param decoder The delegate EntityDecoder
    */
  def decode[F[_], A](implicit decoder: EntityDecoder[F, A]): PartReceiver[F, A] =
    part => Resource.eval(decoder.decode(part, strict = false).value)

  /** Creates a PartReceiver that delegates to an implicitly-resolved `EntityDecoder` to decode the part body,
    * passing `strict = true` to the underlying `decode` method.
    *
    * @param decoder The delegate EntityDecoder
    */
  def decodeStrict[F[_], A](implicit decoder: EntityDecoder[F, A]): PartReceiver[F, A] =
    part => Resource.eval(decoder.decode(part, strict = true).value)

  /** Creates a PartReceiver that loads the part's body into an in-memory buffer, up until
    * a specified maximum size, at which point it will instead write the body to a temp file.
    *
    * The temp file created by this decoder (if any) will be released with the Resource returned
    * by this receiver's `receive` method.
    *
    * @param maxSizeBeforeFile Maximum number of bytes to be buffered in memory for each part
    *                          received by the returned receiver. When the in-memory buffer
    *                          would exceed this limit, the buffer is dumped to a temp file
    *                          and all subsequent data pulled from the part body will be
    *                          appended to that file instead of accumulating the memory buffer.
    * @param chunkSize The chunk size used when reading data back from a temporary file created by this receiver
    * @return A PartReceiver which dynamically decides whether to buffer the part's body in memory or in a file
    */
  def toMixedBuffer[F[_]](
      maxSizeBeforeFile: Int,
      chunkSize: Int = 8192,
  )(implicit
      files: Files[F],
      mt: MonadThrow[F],
      c: Compiler[F, F],
  ): PartReceiver[F, Stream[F, Byte]] =
    part => readToBuffer[F](part.body, maxSizeBeforeFile, chunkSize).map(Right(_))

  private def limitPartSize[F[_]](
      maxPartSizeBytes: Long
  )(implicit F: ApplicativeError[F, Throwable]): Pipe[F, Byte, Byte] = {
    def go(s: Stream[F, Byte], accumSize: Long): Pull[F, Byte, Unit] = s.pull.uncons.flatMap {
      case Some((chunk, tail)) =>
        val newSize = accumSize + chunk.size
        if (newSize <= maxPartSizeBytes) Pull.output(chunk) >> go(tail, newSize)
        else Pull.raiseError[F](InvalidMessageBodyFailure("Part body exceeds maximum length"))
      case None =>
        Pull.done
    }

    go(_, 0L).stream
  }

  private def readToBuffer[F[_]: Files: MonadThrow](
      input: Stream[F, Byte],
      maxSizeBeforeFile: Int,
      chunkSize: Int,
  )(implicit c: Compiler[F, F]): Resource[F, Stream[F, Byte]] = {
    final case class Acc(bytes: Stream[Pure, Byte], bytesSize: Int)
    def go(acc: Acc, s: Stream[F, Byte]): Pull[F, Resource[F, Stream[F, Byte]], Unit] =
      s.pull.uncons.flatMap {
        case Some((headChunk, tail)) =>
          val newSize = acc.bytesSize + headChunk.size
          val newBytes = acc.bytes ++ Stream.chunk(headChunk)
          if (newSize > maxSizeBeforeFile) {
            // dump accumulated buffer to a temp file and continue
            // the pull, writing chunks to the file instead of accumulating
            val toDump = newBytes ++ tail
            Pull.output1(
              Files[F].tempFile
                .evalTap { path =>
                  toDump.through(Files[F].writeAll(path)).compile.drain
                }
                .map(Files[F].readAll(_, chunkSize, Flags.Read))
            )
          } else {
            // add the incoming chunk to the in-memory buffer and recurse
            val newAcc = Acc(acc.bytes ++ Stream.chunk(headChunk), newSize)
            go(newAcc, tail)
          }

        case None =>
          Pull.output1(Resource.pure(acc.bytes.covary[F]))
      }
    Resource.suspend {
      go(Acc(Stream.empty, 0), input).stream.compile.lastOrError
    }
  }
}
