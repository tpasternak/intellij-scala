package org.jetbrains.sbt.shell.action

import java.awt.event.{InputEvent, KeyEvent}

import com.intellij.debugger.DebuggerManagerEx
import com.intellij.debugger.engine.RemoteDebugProcessHandler
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.execution.configurations.RemoteConnection
import com.intellij.execution.console.LanguageConsoleView
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.impl.ExecutionManagerImpl
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.remote.{RemoteConfiguration, RemoteConfigurationType}
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.{ExecutionManager, ProgramRunnerUtil, RunManager, RunnerAndConfigurationSettings}
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem._
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actions.ScrollToTheEndToolbarAction
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.project.{DumbAwareAction, Project}
import javax.swing.{Icon, KeyStroke}
import org.jetbrains.plugins.scala.extensions.executeOnPooledThread
import org.jetbrains.sbt.shell.action.SbtShellActionUtil._
import org.jetbrains.sbt.shell.{SbtProcessManager, SbtShellCommunication, SbtShellToolWindowFactory}

import scala.collection.JavaConverters._

class SbtShellScrollToTheEndToolbarAction(editor: Editor) extends ScrollToTheEndToolbarAction(editor) {

  private val end = KeyStroke.getKeyStroke(KeyEvent.VK_END, InputEvent.CTRL_DOWN_MASK)
  private val shortcuts = new CustomShortcutSet(end)
  setShortcutSet(shortcuts)
}

/**
  * Starts or restarts sbt shell depending on running state.
  */
class StartAction(project: Project) extends DumbAwareAction {

  val templatePresentation: Presentation = getTemplatePresentation
  templatePresentation.setIcon(AllIcons.Actions.Execute)
  templatePresentation.setText("Start sbt shell") // TODO i18n / language-bundle

  override def actionPerformed(e: AnActionEvent): Unit = {
    SbtShellToolWindowFactory.instance(project).foreach { toolWindow =>
      toolWindow.getContentManager.removeAllContents(true)

      executeOnPooledThread {
        SbtProcessManager.forProject(e.getProject).restartProcess()
      }
    }
  }

  override def update(e: AnActionEvent): Unit = {
    val presentation = e.getPresentation
    if (shellAlive(project)) {
      presentation.setIcon(AllIcons.Actions.Restart)
      presentation.setText("Restart sbt shell")
    } else {
      presentation.setIcon(AllIcons.Actions.Execute)
      presentation.setText("Start sbt shell")
    }
  }

}

class StopAction(project: Project) extends DumbAwareAction {
  copyFrom(ActionManager.getInstance.getAction(IdeActions.ACTION_STOP_PROGRAM))
  val templatePresentation: Presentation = getTemplatePresentation
  templatePresentation.setIcon(AllIcons.Actions.Suspend)
  templatePresentation.setText("Stop sbt shell") // TODO i18n / language-bundle
  templatePresentation.setDescription(null)

  override def actionPerformed(e: AnActionEvent): Unit = {
    if (isEnabled) {
      executeOnPooledThread {
        SbtProcessManager.forProject(e.getProject).destroyProcess()
      }
    }
  }

  override def update(e: AnActionEvent): Unit = {
    e.getPresentation.setEnabled(isEnabled)
  }

  private def isEnabled: Boolean = shellAlive(project)
}

class ExecuteTaskAction(console: LanguageConsoleView, task: String, icon: Option[Icon]) extends DumbAwareAction {

  getTemplatePresentation.setIcon(icon.orNull)
  getTemplatePresentation.setText(s"Execute $task")

  override def actionPerformed(e: AnActionEvent): Unit = {
    // TODO execute with indicator
    EditorUtil.scrollToTheEnd(console.getHistoryViewer)
    SbtShellCommunication.forProject(e.getProject).command(task)
  }

  override def update(e: AnActionEvent): Unit = {
    e.getPresentation.setEnabled(isEnabled)
  }

  private def isEnabled: Boolean = shellAlive(console.getProject)
}

class EOFAction(project: Project) extends DumbAwareAction {

  private val templatePresentation: Presentation = getTemplatePresentation
  templatePresentation.setIcon(AllIcons.Actions.TraceOver) // TODO sensible icon
  templatePresentation.setText("Ctrl+D EOF")
  templatePresentation.setDescription("")

  private val ctrlD = KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK)
  private val shortcuts = new CustomShortcutSet(ctrlD)
  setShortcutSet(shortcuts)

  override def actionPerformed(e: AnActionEvent): Unit = {
    SbtShellCommunication.forProject(project).send("\u0004")
  }
}

class SigIntAction(project: Project) extends DumbAwareAction {

  private val templatePresentation: Presentation = getTemplatePresentation
  templatePresentation.setText("Interrupt task")
  templatePresentation.setIcon(AllIcons.Debugger.KillProcess)
  setShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK)))

  override def actionPerformed(e: AnActionEvent): Unit = {
    SbtShellCommunication.forProject(project).sendSigInt()
  }
}

class DebugShellAction(project: Project, remoteConnection: Option[RemoteConnection]) extends ToggleAction {

  private val templatePresentation: Presentation = getTemplatePresentation
  templatePresentation.setIcon(AllIcons.Actions.StartDebugger)
  if (remoteConnection.isDefined) {
    templatePresentation.setText("Attach debugger to sbt shell")
    templatePresentation.setEnabled(false)
  } else {
    templatePresentation.setText("Enable sbt shell debugging in sbt settings and restart shell to attach debugging")
  }

  private val configType = new RemoteConfigurationType
  private val configName = "Debug sbt shell"

  override def setSelected(e: AnActionEvent, toSet: Boolean): Unit = {
    val active = isSelected(e)
    if (toSet && !active) attach()
    else if (!toSet && active) detach()
  }

  override def isSelected(e: AnActionEvent): Boolean = {
    findSession.fold(false) { session => session.isAttached || session.isConnecting }
  }

  private def attach(): Unit = remoteConnection.foreach { remote =>

    val runManager = RunManager.getInstance(project)

    val runConfig =
      findRunConfig
        .getOrElse {
          val rc = runManager.createConfiguration(configName, configType)
          rc.setTemporary(true)
          rc.setActivateToolWindowBeforeRun(false)
          rc.getConfiguration.setAllowRunningInParallel(false)
          runManager.setTemporaryConfiguration(rc)
          rc
        }

    val settings = runConfig.getConfiguration.asInstanceOf[RemoteConfiguration]
    settings.PORT = remote.getAddress
    settings.HOST = remote.getHostName
    settings.SERVER_MODE = remote.isServerMode
    settings.USE_SOCKET_TRANSPORT = remote.isUseSockets

    val environmentBuilder = ExecutionEnvironmentBuilder.create(DefaultDebugExecutor.getDebugExecutorInstance, runConfig)
    ProgramRunnerUtil.executeConfiguration(environmentBuilder.build(), false, false)
  }

  private def detach(): Unit = {
    val executionManager = ExecutionManager.getInstance(project)
    val descriptors = executionManager.getContentManager.getAllDescriptors

    // This is pretty hacky, is there a cleaner way to handle the detaching?
    for {
      proc <- findProcessHandlerByNameAndType(descriptors.asScala)
    } ExecutionManagerImpl.stopProcess(proc)
  }

  private def findProcessHandlerByNameAndType(descriptors: Seq[RunContentDescriptor]): Option[ProcessHandler] =
    for {
      desc <- descriptors.find(_.getDisplayName == configName)
      proc <- Option(desc.getProcessHandler)
      if proc.isInstanceOf[RemoteDebugProcessHandler]
      if !(proc.isProcessTerminated || proc.isProcessTerminating)
    } yield proc


  private def findRunConfig: Option[RunnerAndConfigurationSettings] = {
    val runManager = RunManager.getInstance(project)
    Option(runManager.findConfigurationByTypeAndName(configType.getId,  configName))
  }

  private def findSession: Option[DebuggerSession] = {
    val sessions = DebuggerManagerEx.getInstanceEx(project).getSessions
    sessions.asScala.find(_.getSessionName == configName)
  }

  override def update(e: AnActionEvent): Unit = {
    e.getPresentation.setEnabled(isEnabled)
  }

  private def isEnabled: Boolean = remoteConnection.isDefined && shellAlive(project)
}

private object SbtShellActionUtil {
  def shellAlive(project: Project): Boolean = SbtProcessManager.forProject(project).isAlive
}