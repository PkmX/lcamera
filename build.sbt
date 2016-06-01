import android.Keys._
import android.Dependencies.aar

name := "lcamera"

scalaVersion := "2.11.8"

resolvers += "Akka Snapshot Repository" at "http://repo.akka.io/snapshots/"

libraryDependencies ++= Seq(
  "org.scaloid" %% "scaloid" % "3.6.1-10",
  "com.scalarx" %% "scalarx" % "0.2.6",
  "com.typesafe.akka" %% "akka-actor" % "2.3.6",
  "com.melnykov" % "floatingactionbutton" % "1.0.6",
  "com.github.rahatarmanahmed" % "circularprogressview" % "1.0.0"
)

platformTarget in Android := "android-21"

proguardCache in Android ++=
  Seq ( "org.scaloid.scaloid"
      , "com.scalarx.scalarx"
      , "com.typesafe.akka.akka-actor"
      )

proguardOptions in Android ++=
  Seq ( "-dontobfuscate"
      , "-dontoptimize"
      , "-keepattributes Signature,InnerClasses,EnclosingMethod"
      , "-dontwarn scala.collection.**"
      , "-dontwarn sun.misc.Unsafe"
      )

scalacOptions ++= Seq("-target:jvm-1.7", "-feature", "-deprecation", "-Xlint", "-Xfuture", "-Ywarn-dead-code", "-Ywarn-unused")

javacOptions ++= Seq("-source", "1.7")

run <<= run in Android

install <<= install in Android

//Keys.`package` <<= `packageT` in Android
