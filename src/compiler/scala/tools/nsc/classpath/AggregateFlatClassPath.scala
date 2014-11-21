/*
 * Copyright (c) 2014 Contributor. All rights reserved.
 */
package scala.tools.nsc.classpath

import scala.reflect.io.AbstractFile

case class AggregateFlatClassPath(aggregates: Seq[FlatClassPath]) extends FlatClassPath {

  override def packages(inPackage: String): Seq[PackageEntry] = {
    val aggregatedPkgs = aggregates.map(_.packages(inPackage)).flatten.distinct
    aggregatedPkgs
  }

  override def classes(inPackage: String): Seq[ClassFileEntry] = {
    val aggreagatedClasses = aggregates.map(_.classes(inPackage)).flatten
    aggreagatedClasses
  }
  private def distinctClassEntries(classEntries: Seq[ClassFileEntry]): Seq[ClassFileEntry] = {
    val collectedClassNames = collection.mutable.Set.empty[String]
    val collectedEntries = collection.mutable.ArrayBuffer.empty[ClassFileEntry]
    classEntries foreach { classEntry =>
      val className = classEntry.name
      if (!collectedClassNames.contains(className)) {
        collectedClassNames += className
        collectedEntries += classEntry
      }
    }
    collectedEntries
  }

  override def list(inPackage: String): (Seq[PackageEntry], Seq[ClassFileEntry]) = {
    val (packages, classes) = aggregates.map(_.list(inPackage)).unzip
    val distinctPackages = packages.flatten.distinct
    val distinctClasses = distinctClassEntries(classes.flatten)
    (distinctPackages, distinctClasses)
  }

  override def findClassFile(className: String): Option[AbstractFile] = {
    val (pkg, simpleClassName) = PackageNameUtils.separatePkgAndClassNames(className)
    classes(pkg).find(_.name == simpleClassName).map(_.file)
  }
}
