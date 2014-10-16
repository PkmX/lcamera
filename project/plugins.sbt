resolvers += "Sonatype snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

addSbtPlugin("com.hanhuy.sbt" % "android-sdk-plugin" % "1.3.5")

addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.7.0-SNAPSHOT")

libraryDependencies += "net.sf.proguard" % "proguard-base" % "5.0"
