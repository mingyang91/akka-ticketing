ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.8"

val AkkaVersion = "2.6.19"
val AkkaManagementVersion = "1.1.4"

lazy val root = (project in file("."))
  .enablePlugins(AkkaGrpcPlugin)
  .settings(
    name := "ticketing",
    libraryDependencies ++= Seq(
      ("com.lightbend.akka" %% "akka-projection-cassandra" % "1.2.5"), //.cross(CrossVersion.for3Use2_13),
//        .exclude("org.scala-lang.modules", "scala-collection-compat_2.13")
//        .exclude("com.thesamet.scalapb", "lenses_2.13")
//        .exclude("com.thesamet.scalapb", "scalapb-runtime_2.13"),
      "com.lightbend.akka" %% "akka-projection-eventsourced" % "1.2.5",
      "com.typesafe.akka" %% "akka-persistence-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-persistence-cassandra" % "1.0.6",
      "com.typesafe.akka" %% "akka-persistence-query" % AkkaVersion,
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % AkkaVersion,
      "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % AkkaManagementVersion,
      "ch.qos.logback"                 % "logback-classic" % "1.4.0",
      "software.aws.mcs" % "aws-sigv4-auth-cassandra-java-driver-plugin" % "4.0.6",
      "org.postgresql" % "postgresql" % "42.5.0",
      "io.getquill" %% "quill-jdbc-zio" % "4.4.0",
      "org.openjdk.jmh" % "jmh-core" % "1.35",
      "org.openjdk.jmh" % "jmh-generator-annprocess" % "1.35" % "provided"
    ),
    dependencyOverrides ++= Seq(
      "com.typesafe.akka" %% "akka-http-spray-json" % "10.2.9",
    )
  )

run / fork := true