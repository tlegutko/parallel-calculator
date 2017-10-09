lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      scalaVersion := "2.12.3",
      version      := "0.1.0"
                )),
    name := "test-app",
    fork := true,
    javaOptions in test += "-Xmx8G -XX:+UseConcMarkSweepGC",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.0.1" % "test",
      "com.typesafe.akka" %% "akka-http" % "10.0.10",
      "com.typesafe.akka" %% "akka-http-testkit" % "10.0.10" % Test,
      "com.typesafe.akka" %% "akka-stream" % "2.5.6",
      "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.6" % Test,
      "com.typesafe.akka" %% "akka-http-spray-json" % "10.0.10" 
    )
  )
