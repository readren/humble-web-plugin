package readren

import sbt._
import Keys._
import java.nio.file.Files
import java.io.IOException
import java.io.ByteArrayInputStream

/**
 * An SBT plug-in useful for those project that use both, SBT and NPM, to manage the project building.
 * // TODO translate to English.
 * Es un abordaje mucho mas humilde que el que pretende sbt-web. No intenta crear un ecosistema paralelo al de npm mediante webjars donde aquello que esta hecho para npm hay que adaptarlo a sbt.
 * Este plugin no pretende evitar el uso de npm ni tampoco de parte alguna de su ecosistema. Al contrario, adapta sbt a las pretenciones de npm para convivir con él, empezando por la estructura de directorios de los fuentes (ver `overridenProjectSettings`). 
 */
object HumbleWebPlugin extends AutoPlugin {

  /**
   * Defines all settings/tasks that get automatically imported,
   * when the plugin is enabled
   */
  object autoImport {
    val appDirectory = settingKey[File]("Directory containing all the local application files (all except the global ones).")
    val webBaseDirectory = settingKey[File]("Base directory for the files fetched from the web. This directory is added to the runtime `managedClasspath`")
    val managedAssetsDirectory = settingKey[File]("Directory containing the web assets generated by the build.")
    val exposedNodeModulesDirectory =  settingKey[File]("Directory containing the node modules exposed to the web. By default it is a symbolic link of the project's `node_modules` directory.")
    val exposeNodeModules = taskKey[File]("Generates the exposed node modules. By default it mirrors the project's `node_modules` directory by means of a symbolic link.")
    val assetsSourceDirectories = settingKey[Seq[File]]("directory containing unmanaged web asset files")
    val directAssetCriteria = settingKey[SimpleFileFilter]("criteria used to determine which files are direct web assets")
    val collectDirectAssets = taskKey[Seq[File]]("copies all direct web asset files contained in the `assetsSourceDirectories` to the `webTarget` directory. A file is considered a direct web asset if the `directAssetCriteria` gives true when applied to the that file. This task allows having direct web assets (the ones that are not processed, like java script or HTML files) together with files that are the input of source generators (like type-script files) in the same directory, provided the `directAssetsCriteria` is defined. ")
    val tsc = taskKey[Unit]("runs the typescript compiler") // TODO move to another more specific plug-in which depends on this one.
  }

  import autoImport._

  /**
   * Provide default settings
   */
  override def projectSettings: Seq[Setting[_]] =
    Seq(
      appDirectory := sourceDirectory.value / "app",
      assetsSourceDirectories := Seq(sourceDirectory.value), // by default, there is only one assets source directory: the `sourceDirectory`
      directAssetCriteria := new SimpleFileFilter(defaultDirectAssetCriteria),
      webBaseDirectory := target.value / "web",
      managedAssetsDirectory := webBaseDirectory.value / "assets",
      tsc := tscTask(managedAssetsDirectory.value),
      collectDirectAssets := collectDirectAssetsTask(assetsSourceDirectories.value, managedAssetsDirectory.value, directAssetCriteria.value),
      exposedNodeModulesDirectory := webBaseDirectory.value / "lib",
      exposeNodeModules := createVirtualDirectory(baseDirectory.value / "node_modules", exposedNodeModulesDirectory.value, streams.value.log)
    )

  /** SBT resets any change to standard settings done in the `projectSettings` method. So users of this plug-in should append this settings explicitly in order to reconfigure the source directory structure. */
  val overridenProjectSettings: Seq[Setting[_]] =
    Seq(
      scalaSource in Compile := appDirectory.value,
      scalaSource in Test := appDirectory.value,
      javaSource in Compile := appDirectory.value,
      javaSource in Test := appDirectory.value,
      (managedClasspath in Runtime) += webBaseDirectory.value,
      (managedClasspath in Runtime) <<= (managedClasspath in Runtime).dependsOn(exposeNodeModules, collectDirectAssets, tsc)
    )

  /** By default, only '*.js', '*.html' and '.css' files are considered direct web assets */
  def defaultDirectAssetCriteria(f: File): Boolean = {
    f.ext match {
      case "js"   => true
      case "html" => true
      case "css"  => true
      case _      => false
    }
  }

  /**
   * Copies the files contained by all the `assetsSourceDirectories` that satisfy the `directAssetCriteria` to the `managedAssetsDirectory`.
   * @returns the copies of the assets files which were copied
   */
  private def collectDirectAssetsTask(assetsSourceDirectories: Seq[File], managedAssetsDirectory: File, directAssetCriteria: SimpleFileFilter): Seq[File] = {
    for {
      usd <- assetsSourceDirectories
      originalFile <- (usd ** directAssetCriteria).get
      if !originalFile.isDirectory()
    } yield {
      val relativePath = usd.toPath.relativize(originalFile.toPath)
      val targetFile = managedAssetsDirectory.toPath.resolve(relativePath).toFile
      IO.copyFile(originalFile, targetFile)
      targetFile
    }
  }

  private def tscTask(outDir: File): Unit = {
    Process(s"node node_modules/typescript/lib/tsc.js --outDir ${outDir.getPath}").!
  }

  /**
   * Creates a virtual directory that mirrors the content of another directory. If the virtual directory already exists, nothings happens.
   * @param of a file indicating the real directory path, the one to be imitated
   * @param at a file indicating the virtual directory path
   */
  def createVirtualDirectory(of: File, at: File, logger: Logger): File = {
    if (!at.exists) { // TODO solve the false positive error message "Cannot create a file when that file already exists." which happens on windows because `File.exists` gives false when the cheked file is a directory junction whose target don't exist.
      try {
        Files.createSymbolicLink(at.toPath(), of.toPath)
      } catch {
        // the createSymbolicLink method fails on windows OS if the user has not enough privileges. The mklink command is less demanding. 
        case e: IOException if System.getProperty("os.name").toLowerCase.contains("win") =>
          val cmd = "mklink /J \"" + at + "\" \"" + of + "\"\n"
          val pb = Process.apply("cmd") #< new ByteArrayInputStream(cmd.getBytes())
          pb.lines.find(_.startsWith("Junction created")) foreach { logger.info(_) }
      }
    }
    at
  }

}