import android.Keys._
import android.Dependencies.aar

android.Plugin.androidBuild

name := "lcamera"

scalaVersion := "2.11.2"

resolvers += "Akka Snapshot Repository" at "http://repo.akka.io/snapshots/"

libraryDependencies ++= Seq(
  "org.scaloid" %% "scaloid" % "3.5-10",
  "com.scalarx" %% "scalarx" % "0.2.6",
  "com.typesafe.akka" %% "akka-actor" % "2.3.6",
  aar("com.android.support" % "support-v4" % "20.0.0"),
  aar("com.melnykov" % "floatingactionbutton" % "1.0.4")
)

platformTarget in Android := "android-L"

proguardCache in Android ++=
  Seq ( ProguardCache("org.scaloid") % "org.scaloid" %% "scaloid"
      , ProguardCache("rx") % "com.scalarx" %% "scalarx"
      , ProguardCache("akka") % "com.typesafe.akka" %% "akka-actor"
      )

proguardOptions in Android ++=
  Seq ( "-dontobfuscate"
      , "-dontoptimize"
      , "-keepattributes Signature,InnerClasses,EnclosingMethod"
      , "-dontwarn scala.collection.mutable.**"
      , "-dontwarn sun.misc.Unsafe"
      )

scalacOptions in Compile ++= Seq("-feature", "-deprecation")

run <<= run in Android

install <<= install in Android

Keys.`package` <<= `packageT` in Android