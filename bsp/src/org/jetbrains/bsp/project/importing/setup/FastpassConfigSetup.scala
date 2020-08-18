package org.jetbrains.bsp.project.importing.setup
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.bsp.project.importing.setup.FastpassConfigSetup.FastpassProcessCheckTimeout
import org.jetbrains.plugins.scala.build.{BuildMessages, BuildReporter}
import org.jetbrains.sbt.project.FastpassProjectImportProvider

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

object FastpassConfigSetup {
  private val FastpassProcessCheckTimeout = 100.millis

  def computeBspWorkspace(file: File): String = {
    val projectName = file.getName
    val bspWorkspace = Paths.get(file.getParentFile.getParentFile.getAbsolutePath).resolve("bsp-projects").resolve(projectName)
    bspWorkspace.toString
  }

  def create(baseDir: File): Try[FastpassConfigSetup] = {
    val baseDirVFile = LocalFileSystem.getInstance().findFileByIoFile(baseDir)
    FastpassProjectImportProvider.pantsRoot(baseDirVFile) match {
      case Some(pantsRoot) => {
        val relativeDir = pantsRoot.toNioPath.relativize(baseDirVFile.toNioPath)
        val processBuilder = new ProcessBuilder(
          "coursier",
          "launch",
          "org.scalameta:fastpass_2.12:1.5.0",
          "-r",
          "sonatype:snapshots",
          "--main",
          "scala.meta.fastpass.Fastpass",
          "--",
          "create",
          relativeDir.toString + "::"
        )
        processBuilder.directory(new File(pantsRoot.toNioPath.toString))
        Success(new FastpassConfigSetup(processBuilder))
      }
      case None => Failure(new IllegalArgumentException(s"'${baseDir} is not a pants directory'"))
    }
  }
}

class FastpassConfigSetup(processBuilder: ProcessBuilder) extends BspConfigSetup {
  override def cancel(): Unit = cancellationFlag.set(true)

  private val cancellationFlag: AtomicBoolean = new AtomicBoolean(false)

  private def waitFinish(process: Process): Option[BuildMessages] =
    Iterator.continually(process.waitFor(FastpassProcessCheckTimeout.length, FastpassProcessCheckTimeout.unit))
      .map { end =>
        if (! end && cancellationFlag.get()) {
          process.destroy()
          true
        } else end
      }
      .find(identity)
      .map(_ => BuildMessages.empty.status(BuildMessages.OK))

  override def run(implicit reporter: BuildReporter): Try[BuildMessages] = {
    reporter.start()
    val process = processBuilder.start()
    val result = Try(waitFinish(process).get)
    result match {
      case Failure(err) => reporter.finishWithFailure(err)
      case Success(bm) => reporter.finish(bm)
    }
    result
  }
}
