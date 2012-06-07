import sbt._

import Keys._
import AndroidKeys._

object General {
  val settings = Defaults.defaultSettings ++ Seq (
    version := "0.1",
    scalaVersion := "2.9.0-1",
    platformName in Android := "android-7"
  )

  lazy val fullAndroidSettings =
    General.settings ++
    AndroidProject.androidSettings ++
    TypedResources.settings ++
    AndroidMarketPublish.settings ++ Seq (
      keyalias in Android := "change-me",
      libraryDependencies ++= Seq(
        "org.scalatest"     %% "scalatest"        % "1.6.1"        % "test",
        "org.positronicnet" %% "roboscalatest"    % "0.4-SNAPSHOT" % "test",
        "com.pivotallabs"   %  "robolectric"      % "1.0-RC1"      % "test",
        "org.positronicnet" %% "positronicnetlib" % "0.4-SNAPSHOT"
      ),
      testOptions in Test ++= Seq(
        Tests.Argument("-DandroidResPath=src/main/res"),
        Tests.Argument("-DandroidManifestPath=src/main/AndroidManifest.xml")),
      proguardOption in Android := """
       -keepclassmembers class * implements java.io.Serializable {
        private static final java.io.ObjectStreamField[] serialPersistentFields;
        private void writeObject(java.io.ObjectOutputStream);
        private void readObject(java.io.ObjectInputStream);
        java.lang.Object writeReplace();
        java.lang.Object readResolve();
       }
      """
    )
}

object AndroidBuild extends Build {
  lazy val proj = Project (
    "Positronic Contacts",
    file("."),
    settings = General.fullAndroidSettings )
}
