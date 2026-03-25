package io.github.quafadas

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

import org.scalajs.linker.interface.OutputDirectory
import org.scalajs.linker.interface.unstable.OutputDirectoryImpl

sealed trait MemOutputDirectory extends OutputDirectory:
  def content(name: String): Option[ByteBuffer]
  def fileNames(): List[String]
  def put(name: String, buf: ByteBuffer): Unit
  def remove(name: String): Unit
end MemOutputDirectory

object MemOutputDirectory:

  // private val factory = XXHashFactory.fastestInstance()
  // private val hashSeed = 0L

  def apply(): MemOutputDirectory =
    new Impl()

  private final class Impl extends OutputDirectoryImpl with MemOutputDirectory:

    private val files: ConcurrentHashMap[String, ByteBuffer] =
      new ConcurrentHashMap[String, ByteBuffer]()

    // private def hashBuffer(buf: ByteBuffer): Long = {
    //   val view = buf.asReadOnlyBuffer()
    //   val hasher = factory.hash64()
    //   hasher.hash(view, view.position(), view.remaining(), hashSeed)
    // }

    // def hashFiles(
    //     files: Map[String, ByteBuffer]
    // )(implicit ec: ExecutionContext): Future[Map[String, Long]] =
    //   Future
    //     .traverse(files.iterator.toList) { case (name, buf) =>
    //       Future(name -> hashBuffer(buf))
    //     }
    //     .map(_.toMap)

    override def content(name: String): Option[ByteBuffer] =
      Option(files.get(name)).map(_.asReadOnlyBuffer())

    override def fileNames(): List[String] =
      files.keySet().asScala.toList

    override def put(name: String, buf: ByteBuffer): Unit =
      files.put(name, buf.asReadOnlyBuffer())

    override def remove(name: String): Unit =
      files.remove(name)

    override def writeFull(
        name: String,
        buf: ByteBuffer
    )(implicit ec: ExecutionContext): Future[Unit] =

      // create immutable view without copying
      val stored =
        buf.asReadOnlyBuffer()

      files.put(name, stored)

      Future.successful(())
    end writeFull

    override def readFull(
        name: String
    )(implicit ec: ExecutionContext): Future[ByteBuffer] =

      val buf = files.get(name)

      if buf eq null then Future.failed(new IOException(s"file $name does not exist"))
      else Future.successful(buf.asReadOnlyBuffer())
      end if
    end readFull

    override def listFiles()(implicit
        ec: ExecutionContext
    ): Future[List[String]] =
      Future.successful(files.keySet().asScala.toList)

    override def delete(
        name: String
    )(implicit ec: ExecutionContext): Future[Unit] =

      if files.remove(name) != null then Future.successful(())
      else Future.failed(new IOException(s"file $name does not exist"))
  end Impl
end MemOutputDirectory
