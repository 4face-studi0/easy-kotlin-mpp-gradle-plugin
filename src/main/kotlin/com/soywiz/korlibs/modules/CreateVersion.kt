package com.soywiz.korlibs.modules

import com.soywiz.korlibs.*
import com.soywiz.korlibs.util.*
import org.gradle.api.*

fun Project.configureCreateVersion() {
	fun command(vararg commands: String) {
		println("Executing... " + commands.joinToString(" "))
		exec {
			it.workingDir(rootDir)
			it.commandLine(*commands)
		}
	}

	fun releaseVersion(version: SemVer) {
		val nextSnapshotVersion = version.withIncrementedVersion().withSnapshot()
		println("Releasing $version ... next snapshot version $nextSnapshotVersion")
		if (shellExec("git", "status", "-s").outputIfNotError.isNotEmpty()) {
			error("Must commit pending changes before releasing a new version")
		}
		command("git", "pull")
		//command("git", "push")
		PropertiesUpdater.update(rootDir["gradle.properties"], mapOf("version" to version.version))
		command("./gradlew") // To refresh versions
		command("git", "add", "-A")
		command("git", "commit", "-m", "Release $version")
		command("git", "tag", "-a", "$version", "-m \"Release $version\"")
		command("git", "push")
		PropertiesUpdater.update(rootDir["gradle.properties"], mapOf("version" to nextSnapshotVersion.version))
		command("./gradlew") // To refresh versions
		command("git", "add", "-A")
		command("git", "commit", "-m", "Started $nextSnapshotVersion")
		command("git", "push")
	}

	tasks.create("releaseVersion") { task ->
		task.group = "versioning"
		task.doLast {
			val releaseVersion = project.findProperty("nextReleaseVersion") ?: error("Must specify nextReleaseVersion: ./gradlew releaseVersion -PnextReleaseVersion=x.y.z")
			releaseVersion(SemVer(releaseVersion.toString()))
		}
	}

	tasks.create("releaseQuickVersion") { task ->
		task.group = "versioning"
		task.doLast {
			releaseVersion(SemVer(version.toString()).withoutSnapshot())
		}
	}
}