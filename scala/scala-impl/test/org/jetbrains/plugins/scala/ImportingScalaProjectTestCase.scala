package org.jetbrains.plugins.scala

import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.pom.java.LanguageLevel
import org.jetbrains.plugins.scala.performance.ImportingProjectTestCase
import org.junit.Ignore

@Ignore
class ImportingScalaProjectTestCase
  extends ImportingProjectTestCase {

  override def rootDirPath: String = "/Users/artyom.semyonov/Desktop"

  override def projectName: String = "scala-plugin-for-ultimate"

  override def jdkLanguageLevel: LanguageLevel = LanguageLevel.JDK_1_8

  def testTest(): Unit = {
    val appManager = ApplicationManagerEx.getApplicationEx
    val oldSaveAllowed = appManager.isSaveAllowed
    appManager.setSaveAllowed(true)
    appManager.saveSettings() // No need
    myProject.save()
    println("Done!")
    appManager.setSaveAllowed(oldSaveAllowed)
  }
}
