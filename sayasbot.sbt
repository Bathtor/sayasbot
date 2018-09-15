enablePlugins(BuildInfoPlugin)

name := "SayAsBot"

organization := "com.lkroll"

version := "1.0.0"

scalaVersion := "2.12.6"

resolvers += "Spring Plugins Release" at "http://repo.spring.io/plugins-release/"

libraryDependencies += "com.lihaoyi" %% "fastparse" % "1.+"
libraryDependencies += "org.rogach" %% "scallop" % "3.1.+"
libraryDependencies += "com.typesafe.akka" %% "akka-persistence" % "2.5.16"
libraryDependencies += "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8"
libraryDependencies += "net.katsstuff" %% "ackcord" % "0.11.0-SNAPSHOT"
libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.9.0"
libraryDependencies += "com.typesafe" % "config" % "1.3.2"
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.+"
libraryDependencies += "com.lihaoyi" %% "fastparse" % "1.+"
libraryDependencies += "org.scalactic" %% "scalactic" % "3.0.4" % "test"
libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.4" % "test"


buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion)
buildInfoPackage := "com.lkroll.sayasbot"

mainClass in assembly := Some("com.lkroll.sayasbot.Main")
assemblyMergeStrategy in assembly := { 
    case PathList("com", "google", "common", xs @ _*)	=> MergeStrategy.first
    case PathList("com", "typesafe", "config", xs @ _*)	=> MergeStrategy.first
    //case n if n.startsWith("reference.conf")			=> MergeStrategy.concat
    case "logback.xml"									=> MergeStrategy.filterDistinctLines
    case x => {
      val oldStrategy = (assemblyMergeStrategy in assembly).value
      oldStrategy(x)
  	}
}