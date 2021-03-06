package org.jetbrains.jps.incremental.scala.data

import org.jetbrains.jps.incremental.scala.remote.{CommandIds, CompileServerCommand}
import org.jetbrains.plugins.scala.compiler.data.serialization.SerializationUtils

import scala.concurrent.duration.DurationLong
import scala.util.Try

trait CompileServerCommandParser {

  def parse(command: String, args: Seq[String]): Try[CompileServerCommand]
}

object CompileServerCommandParser
  extends CompileServerCommandParser {

  def parse(commandId: String, args: Seq[String]): Try[CompileServerCommand] = Try {
    commandId match {
      case CommandIds.Compile =>
        ArgumentsParser.parse(args) match {
          case Right(arguments) => CompileServerCommand.Compile(arguments)
          case Left(t) => throw t
        }
      case CommandIds.CompileJps =>
        args match {
          case Seq(token, projectPath, globalOptionsPath, dataStorageRootPath, testScopeOnly, forceCompileModule) =>
            CompileServerCommand.CompileJps(
              token = token,
              projectPath = projectPath,
              globalOptionsPath = globalOptionsPath,
              dataStorageRootPath = dataStorageRootPath,
              testScopeOnly = testScopeOnly.toBoolean,
              forceCompileModule = Option(forceCompileModule).filter(_.nonEmpty)
            )
          case _ =>
            throwIllegalArgs(commandId, args)
        }
      case CommandIds.StartMetering =>
        args match {
          case Seq(token, meteringInterval) =>
            CompileServerCommand.StartMetering(token, meteringInterval.toLong.seconds)
          case _ =>
            throwIllegalArgs(commandId, args)
        }
      case CommandIds.EndMetering =>
        args match {
          case Seq(token) =>
            CompileServerCommand.EndMetering(token)
          case _ =>
            throwIllegalArgs(commandId, args)
        }
      case unknownCommand =>
        throw new IllegalArgumentException(s"Unknown commandId: $unknownCommand")
    }
  }

  private def throwIllegalArgs(commandId: String, args: Seq[String]): Nothing =
    throw new IllegalArgumentException(s"Can't parse args for $commandId: ${args.toList}")
}

