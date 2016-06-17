sbtPlugin := true

organization := "readren.org"
name := """humble-web-plugin"""
version := "0.1-SNAPSHOT"

scalaVersion := "2.10.6" // the one used by sbt version 0.13.11
resolvers += Resolver.sonatypeRepo("snapshots")

scalacOptions ++= Seq("-feature")


