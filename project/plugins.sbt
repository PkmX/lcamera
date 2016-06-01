resolvers += "Sonatype snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

addSbtPlugin("org.scala-android" % "sbt-android" % "1.6.3")

addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.7.0-SNAPSHOT")

libraryDependencies += "net.sf.proguard" % "proguard-base" % "5.2.1"
