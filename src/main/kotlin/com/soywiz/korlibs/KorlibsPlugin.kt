package com.soywiz.korlibs

import com.soywiz.korlibs.modules.*
import com.soywiz.korlibs.targets.*
import org.gradle.api.*
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCompilation
import java.io.*
import com.moowork.gradle.node.*
import com.moowork.gradle.node.npm.*
import com.soywiz.korlibs.util.*
import org.jetbrains.kotlin.gradle.plugin.*

open class KorlibsPluginNoNativeNoAndroid : BaseKorlibsPlugin(nativeEnabled = false, suggestAndroidEnabled = false)
open class KorlibsPluginNoNative : BaseKorlibsPlugin(nativeEnabled = false, suggestAndroidEnabled = null)
open class KorlibsPlugin : BaseKorlibsPlugin(nativeEnabled = true, suggestAndroidEnabled = null)

fun NamedDomainObjectContainer<KotlinSourceSet>.dependants(name: String, on: Set<String>) {
	val main = maybeCreate("${name}Main")
	val test = maybeCreate("${name}Test")
	for (o in on) {
		maybeCreate("${o}Main").dependsOn(main)
		maybeCreate("${o}Test").dependsOn(test)
	}
}

open class BaseKorlibsPlugin(val nativeEnabled: Boolean, val suggestAndroidEnabled: Boolean?) : Plugin<Project> {
    override fun apply(project: Project) = project {
		val androidDisabled = (suggestAndroidEnabled == false) || (project.findProperty("disable.android") == "true") || (System.getenv("DISABLE_ANDROID") == "true")
		val androidEnabled = !androidDisabled

		val korlibs = KorlibsExtension(this, nativeEnabled, androidEnabled)
        extensions.add("korlibs", korlibs)

        plugins.apply("kotlin-multiplatform")
		plugins.apply("com.moowork.node")

		//println("KotlinVersion.CURRENT: ${KotlinVersion.CURRENT}")
		//println("KORLIBS_KOTLIN_VERSION: $KORLIBS_KOTLIN_VERSION")

		//project.setProperty("KORLIBS_KOTLIN_VERSION", KORLIBS_KOTLIN_VERSION)

        configureKorlibsRepos()

        // Platforms
        configureTargetCommon()
		if (korlibs.hasAndroid) {
			configureTargetAndroid()
		}
        if (nativeEnabled) {
            configureTargetNative()
        }
		gkotlin.apply {
			sourceSets.apply {
				dependants("nonJs", korlibs.NON_JS_TARGETS)
				dependants("nonJvm", korlibs.NON_JVM_TARGETS)
				dependants("nonNativeCommon", korlibs.ALL_NON_COMMON_TARGETS - korlibs.ALL_NATIVE_TARGETS)
			}
		}

		configureTargetJavaScript()
        configureTargetJVM()

        // Publishing
        configurePublishing()
		configureBintrayTools()

		// Create version
		configureCreateVersion()
    }
}

val globalKorlibsDir: File by lazy { File(System.getProperty("user.home"), ".korlibs").apply { mkdirs() } }

class KorlibsExtension(val project: Project, val nativeEnabled: Boolean, val androidEnabled: Boolean) {
	val rootProject = project.rootProject
    val korlibsDir: File get() = globalKorlibsDir
    //init { println("KorlibsExtension:${project.name},nativeEnabled=$nativeEnabled,androidEnabled=$androidEnabled") }
	val prop_sdk_dir = System.getProperty("sdk.dir")
	val prop_ANDROID_HOME = getEnv("ANDROID_HOME")
    var hasAndroid = androidEnabled && ((prop_sdk_dir != null) || (prop_ANDROID_HOME != null))
	val tryAndroidSdkDirs = listOf(
			File(System.getProperty("user.home"), "/Library/Android/sdk"),
			File(System.getProperty("user.home"), "/Android/Sdk")
	)
	val linuxEnabled get() = com.soywiz.korlibs.targets.linuxEnabled
	val tvosDisabled = listOf(project, rootProject).mapNotNull { it.findProperty("disable.tvos") }.firstOrNull() == "true"
	val watchosDisabled = listOf(project, rootProject).mapNotNull { it.findProperty("disable.watchos") }.firstOrNull() == "true"
	val nodejsDisabled = listOf(project, rootProject).mapNotNull { it.findProperty("disable.nodejs") }.firstOrNull() == "true"
	val nodejsEnabled = !nodejsDisabled
	val tvosEnabled = !tvosDisabled
	val watchosEnabled = !watchosDisabled
	val supressWarnings = project.findProperty("kotlinSupressWarnings")?.toString()?.toBoolean() ?: true

    init {
        if (!hasAndroid && androidEnabled) {
			for (tryAndroidSdkDirs in tryAndroidSdkDirs) {
				if (tryAndroidSdkDirs.exists()) {
					File(project.rootDir, "local.properties").writeText("sdk.dir=${tryAndroidSdkDirs.absolutePath}")
					hasAndroid = true
					break
				}
			}
        }

		project.logger.info("hasAndroid: $hasAndroid, sdk.dir=$prop_sdk_dir, ANDROID_HOME=$prop_ANDROID_HOME, tryAndroidSdkDir=$tryAndroidSdkDirs (${tryAndroidSdkDirs.any { it.exists() }})")
    }

    fun dependencyProject(name: String) = project {
        dependencies {
            add("commonMainApi", project(name))
            add("commonTestImplementation", project(name))
        }
    }

	val KORLIBS_KOTLIN_VERSION get() = com.soywiz.korlibs.KORLIBS_KOTLIN_VERSION
	val isKotlinDev get() = KORLIBS_KOTLIN_VERSION.contains("-release")
	val isKotlinEap get() = KORLIBS_KOTLIN_VERSION.contains("-eap")
	val LINUX_DESKTOP_NATIVE_TARGETS = if (linuxEnabled) setOf("linuxX64") else setOf()
    val MACOS_DESKTOP_NATIVE_TARGETS = setOf("macosX64")
    //val WINDOWS_DESKTOP_NATIVE_TARGETS = listOf("mingwX64", "mingwX86")
    val WINDOWS_DESKTOP_NATIVE_TARGETS = setOf("mingwX64")
    val DESKTOP_NATIVE_TARGETS = LINUX_DESKTOP_NATIVE_TARGETS + MACOS_DESKTOP_NATIVE_TARGETS + WINDOWS_DESKTOP_NATIVE_TARGETS
    val IOS_TARGETS = setOf("iosArm64", "iosArm32", "iosX64")
	val WATCHOS_TARGETS = if (watchosEnabled) setOf("watchosArm64", "watchosArm32", "watchosX86") else setOf()
	val TVOS_TARGETS = if (tvosEnabled) setOf("tvosArm64", "tvosX64") else setOf()
	val IOS_WATCHOS_TVOS_TARGETS = IOS_TARGETS + WATCHOS_TARGETS + TVOS_TARGETS
	val APPLE_TARGETS = IOS_WATCHOS_TVOS_TARGETS + MACOS_DESKTOP_NATIVE_TARGETS
	val ALL_NATIVE_TARGETS = (APPLE_TARGETS + DESKTOP_NATIVE_TARGETS).toSet()
	val POSIX_NATIVE_TARGETS = ALL_NATIVE_TARGETS - WINDOWS_DESKTOP_NATIVE_TARGETS
	val NATIVE_POSIX_APPLE_TARGETS = APPLE_TARGETS
	val NATIVE_POSIX_NON_APPLE_TARGETS = POSIX_NATIVE_TARGETS - NATIVE_POSIX_APPLE_TARGETS
    val ALL_ANDROID_TARGETS = if (hasAndroid) setOf("android") else setOf()
    val JS_TARGETS = setOf("js")
    val JVM_TARGETS = setOf("jvm")
	val JVM_ANDROID_TARGETS = JVM_TARGETS + ALL_ANDROID_TARGETS
    val COMMON_TARGETS = setOf("metadata")
	val ALL_NON_COMMON_TARGETS = ALL_ANDROID_TARGETS + JS_TARGETS + JVM_TARGETS + ALL_NATIVE_TARGETS
    val ALL_TARGETS = ALL_NON_COMMON_TARGETS + ALL_NON_COMMON_TARGETS
	val NON_JS_TARGETS = ALL_NON_COMMON_TARGETS - JS_TARGETS
	val NON_JVM_TARGETS = ALL_NON_COMMON_TARGETS - JVM_TARGETS

	@JvmOverloads
    fun dependencyMulti(group: String, name: String, version: String, targets: Set<String> = ALL_TARGETS, suffixCommonRename: Boolean = false, androidIsJvm: Boolean = false) = project {
        dependencies {
            for (target in targets) {
                val base = when (target) {
                    "metadata" -> "common"
                    else -> target
                }
                val suffix = when {
                    target == "android" && androidIsJvm -> "-jvm"
                    target == "metadata" && suffixCommonRename -> "-common"
                    else -> "-${target.toLowerCase()}"
                }

                val packed = "$group:$name$suffix:$version"
                add("${base}MainApi", packed)
                add("${base}TestImplementation", packed)
            }
        }
    }

    @JvmOverloads
    fun dependencyMulti(dependency: String, targets: Set<String> = ALL_TARGETS) {
        val (group, name, version) = dependency.split(":", limit = 3)
        return dependencyMulti(group, name, version, targets)
    }

	@JvmOverloads
	fun dependencyNodeModule(name: String, version: String) = project {
		val node = extensions.getByType(NodeExtension::class.java)

		val installNodeModule = tasks.create<NpmTask>("installJs${name.capitalize()}") {
			onlyIf { !File(node.nodeModulesDir, name).exists() }
			setArgs(arrayListOf("install", "$name@$version"))
		}

		tasks.getByName("jsNodeTest").dependsOn(installNodeModule)
	}

    data class CInteropTargets(val name: String, val targets: List<String>)

    val cinterops = arrayListOf<CInteropTargets>()


    fun dependencyCInterops(name: String, targets: List<String>) = project {
        cinterops += CInteropTargets(name, targets)
        for (target in targets) {
            (kotlin.targets[target].compilations["main"] as KotlinNativeCompilation).apply {
                cinterops.apply {
                    maybeCreate(name).apply {
                    }
                }
            }
        }
    }

    @JvmOverloads
    fun dependencyCInteropsExternal(dependency: String, cinterop: String, targets: Set<String> = ALL_NATIVE_TARGETS) {
        dependencyMulti("$dependency:cinterop-$cinterop@klib", targets)
    }

    @JvmOverloads
    fun exposeVersion(name: String = project.name) {
        project.projectDir["src/commonMain/kotlin/com/soywiz/$name/internal/${name.capitalize()}Version.kt"].text = """
            package com.soywiz.$name.internal

            internal const val ${name.toUpperCase()}_VERSION = "${project.version}"
        """.trimIndent()
    }
}

val Project.korlibs get() = extensions.getByType(KorlibsExtension::class.java)
fun Project.korlibs(callback: KorlibsExtension.() -> Unit) = korlibs.apply(callback)
val Project.hasAndroid get() = korlibs.hasAndroid
