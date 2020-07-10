package org.jetbrains.sbt.project

import java.io.File
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.plugins.scala.build.BuildMessages
import org.jetbrains.sbt.project.SbtProjectResolver.ImportCancelledException

import scala.util.{Failure, Try}

object PantsBspProjectImportProvider {
  def canImport(fileOrDirectory: VirtualFile): Boolean = {
    fileOrDirectory.getParent.findChild("pants") != null
  }

  def resolveBspWorkspace(vfile: VirtualFile) =
    new File(Paths.get(vfile.getCanonicalPath).getParent.getParent.resolve("bsp-projects").resolve(vfile.getName).toAbsolutePath.toString)

  def install(workspace: File): Try[BuildMessages] = {
    Try {
      val pb = new ProcessBuilder("coursier", "launch", "org.scalameta:metals_2.12:0.9.0",  "-r",
        "sonatype:snapshots", "--main", "scala.meta.internal.pantsbuild.BloopPants", "--", "create",
        workspace.getName + "::")
      pb.directory(workspace.getParentFile)
      val p = pb.start()
      p.waitFor(1200, TimeUnit.SECONDS)
      BuildMessages.empty.status(BuildMessages.OK)
    }.recoverWith {
      case fail => Failure(ImportCancelledException(fail))
    }
  }
}
