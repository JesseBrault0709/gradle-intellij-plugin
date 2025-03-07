// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.intellij.tasks

import com.jetbrains.plugin.structure.base.utils.exists
import com.jetbrains.plugin.structure.base.utils.listFiles
import org.gradle.api.DefaultTask
import org.gradle.api.JavaVersion
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.*
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.intellij.*
import org.jetbrains.intellij.IntelliJPluginConstants.PLUGIN_GROUP_NAME
import org.jetbrains.intellij.utils.PlatformJavaVersions
import org.jetbrains.intellij.utils.PlatformKotlinVersions
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File
import java.nio.file.Path
import javax.inject.Inject

/**
 * Validates the plugin project configuration:
 *
 * - The [PatchPluginXmlTask.sinceBuild] property can't be lower than the major version of the currently used IntelliJ SDK set with the [IntelliJPluginExtension.version].
 * - The [sourceCompatibility] property of the Java configuration can't be lower than the Java version used for assembling the IntelliJ SDK specified by the [IntelliJPluginExtension.version].
 * - The [targetCompatibility] property of the Java configuration can't be higher than the Java version required for running IDE in the version specified by the [IntelliJPluginExtension.version] or [PatchPluginXmlTask.sinceBuild] properties.
 * - The [kotlinJvmTarget] property of the Kotlin configuration (if used) can't be higher than the Java version required for running IDE in the version specified by the [IntelliJPluginExtension.version] or [PatchPluginXmlTask.sinceBuild] properties.
 * - The [kotlinLanguageVersion] property of the Kotlin configuration (if used) can't be lower than the Kotlin bundled with IDE in the version specified by the [IntelliJPluginExtension.version] or [PatchPluginXmlTask.sinceBuild] properties.
 * - The [kotlinApiVersion] property of the Kotlin configuration (if used) can't be higher than the Kotlin bundled with IDE in the version specified by the [IntelliJPluginExtension.version] or [PatchPluginXmlTask.sinceBuild] properties.
 *
 * For more details regarding the Java version used in the specific IntelliJ SDK, see [Build Number Ranges](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html).
 *
 * The dependency on the Kotlin Standard Library (stdlib) is automatically added when using the Gradle Kotlin plugin and may conflict with the version provided with the IntelliJ Platform.
 *
 * Read more about controlling this behavior on [Kotlin Standard Library](https://plugins.jetbrains.com/docs/intellij/using-kotlin.html#kotlin-standard-library).
 *
 * An old default [RunPluginVerifierTask.downloadDir] path contains downloaded IDEs but another default is in use. Links to the [FAQ section](https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin-faq.html#the-plugin-verifier-download-directory-is-set-to-but-downloaded-ides-were-also-found-in)
 *
 * @see <a href="https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html">Build Number Ranges</a>
 * @see <a href="https://plugins.jetbrains.com/docs/intellij/using-kotlin.html#kotlin-standard-library">Kotlin Standard Library</a>
 */
@CacheableTask
abstract class VerifyPluginConfigurationTask @Inject constructor(
    private val providers: ProviderFactory,
) : DefaultTask() {

    /**
     * The location of the built plugin file which will be used for verification.
     *
     * Default value: `${prepareSandboxTask.destinationDir}/${prepareSandboxTask.pluginName}``
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pluginXmlFiles: ListProperty<File>

    /**
     * IntelliJ SDK platform version.
     */
    @get:Internal
    abstract val platformVersion: Property<String>

    /**
     * IntelliJ SDK platform build number.
     */
    @get:Internal
    abstract val platformBuild: Property<String>

    /**
     * [JavaCompile.sourceCompatibility] property defined in the build script.
     */
    @get:Internal
    abstract val sourceCompatibility: Property<String>

    /**
     * [JavaCompile.targetCompatibility] property defined in the build script.
     */
    @get:Internal
    abstract val targetCompatibility: Property<String>

    /**
     * The Gradle Kotlin plugin is loaded.
     */
    @get:Internal
    abstract val kotlinPluginAvailable: Property<Boolean>

    /**
     * The `apiVersion` property of [KotlinCompile.kotlinOptions] defined in the build script
     */
    @get:Internal
    abstract val kotlinApiVersion: Property<String?>

    /**
     * The `languageVersion` property of [KotlinCompile.kotlinOptions] defined in the build script
     */
    @get:Internal
    abstract val kotlinLanguageVersion: Property<String?>

    /**
     * The version of the Kotlin used in the project.
     */
    @get:Internal
    abstract val kotlinVersion: Property<String?>

    /**
     * The `jvmTarget` property of [KotlinCompile.kotlinOptions] defined in the build script.
     */
    @get:Internal
    abstract val kotlinJvmTarget: Property<String?>

    /**
     * `kotlin.stdlib.default.dependency` property value defined in the `gradle.properties` file.
     */
    @get:Internal
    abstract val kotlinStdlibDefaultDependency: Property<Boolean>

    /**
     * `kotlin.incremental.useClasspathSnapshot` property value defined in the `gradle.properties` file.
     */
    @get:Internal
    abstract val kotlinIncrementalUseClasspathSnapshot: Property<Boolean>

    /**
     * The path to the directory where IDEs used for the verification will be downloaded.
     * Value propagated with [RunPluginVerifierTask.downloadDir].
     */
    @get:Internal
    abstract val pluginVerifierDownloadDir: Property<String>

    private val context = logCategory()

    init {
        group = PLUGIN_GROUP_NAME
        description = "Checks if Java and Kotlin compilers configuration meet IntelliJ SDK requirements"
    }

    @TaskAction
    fun verifyPluginConfiguration() {
        val platformVersion = platformVersion.get().let(Version::parse)
        val platformBuildVersion = platformBuild.get().let(Version::parse)
        val platformJavaVersion = platformBuildVersion.let(::getPlatformJavaVersion)
        val sourceCompatibilityJavaVersion = sourceCompatibility.get().let(JavaVersion::toVersion)
        val targetCompatibilityJavaVersion = targetCompatibility.get().let(JavaVersion::toVersion)
        val jvmTargetJavaVersion = kotlinJvmTarget.orNull?.let(JavaVersion::toVersion)
        val kotlinApiVersion = kotlinApiVersion.orNull?.let(Version::parse)
        val kotlinLanguageVersion = kotlinLanguageVersion.orNull?.let(Version::parse)
        val kotlinVersion = kotlinVersion.orNull?.let(Version::parse)
        val platformKotlinLanguageVersion = platformBuildVersion.let(::getPlatformKotlinVersion)?.run { Version(major, minor) }
        val pluginVerifierDownloadPath = pluginVerifierDownloadDir.get().let(Path::of).toAbsolutePath()
        val oldPluginVerifierDownloadPath = providers.systemProperty("user.home").map { "$it/.pluginVerifier/ides" }.get().let(Path::of).toAbsolutePath()

        sequence {
            pluginXmlFiles.get()
                .mapNotNull { parsePluginXml(it.toPath(), context) }
                .forEach { plugin ->
                    val sinceBuild = plugin.ideaVersion.sinceBuild.let(Version::parse)
                    val sinceBuildJavaVersion = sinceBuild.let(::getPlatformJavaVersion)
                    val sinceBuildKotlinApiVersion = sinceBuild.let(::getPlatformKotlinVersion)

                    if (sinceBuild.major < platformBuildVersion.major) {
                        yield("The 'since-build' property is lower than the target IntelliJ Platform major version: $sinceBuild < ${platformBuildVersion.major}.")
                    }
                    if (sinceBuildJavaVersion < targetCompatibilityJavaVersion) {
                        yield("The Java configuration specifies targetCompatibility=$targetCompatibilityJavaVersion but since-build='$sinceBuild' property requires targetCompatibility=$sinceBuildJavaVersion.")
                    }
                    if (sinceBuildJavaVersion < jvmTargetJavaVersion) {
                        yield("The Kotlin configuration specifies jvmTarget=$jvmTargetJavaVersion but since-build='$sinceBuild' property requires jvmTarget=$sinceBuildJavaVersion.")
                    }
                    if (sinceBuildKotlinApiVersion < kotlinApiVersion) {
                        yield("The Kotlin configuration specifies apiVersion=$kotlinApiVersion but since-build='$sinceBuild' property requires apiVersion=$sinceBuildKotlinApiVersion.")
                    }
                }

            if (platformJavaVersion > sourceCompatibilityJavaVersion) {
                yield("The Java configuration specifies sourceCompatibility=$sourceCompatibilityJavaVersion but IntelliJ Platform $platformVersion requires sourceCompatibility=$platformJavaVersion.")
            }
            if (platformKotlinLanguageVersion > kotlinLanguageVersion) {
                yield("The Kotlin configuration specifies languageVersion=$kotlinLanguageVersion but IntelliJ Platform $platformVersion requires languageVersion=$platformKotlinLanguageVersion.")
            }
            if (platformJavaVersion < targetCompatibilityJavaVersion) {
                yield("The Java configuration specifies targetCompatibility=$targetCompatibilityJavaVersion but IntelliJ Platform $platformVersion requires targetCompatibility=$platformJavaVersion.")
            }
            if (platformJavaVersion < jvmTargetJavaVersion) {
                yield("The Kotlin configuration specifies jvmTarget=$jvmTargetJavaVersion but IntelliJ Platform $platformVersion requires jvmTarget=$platformJavaVersion.")
            }
            if (kotlinPluginAvailable.get() && kotlinStdlibDefaultDependency.orNull == null) {
                yield("The dependency on the Kotlin Standard Library (stdlib) is automatically added when using the Gradle Kotlin plugin and may conflict with the version provided with the IntelliJ Platform, see: https://jb.gg/intellij-platform-kotlin-stdlib")
            }
            if (kotlinPluginAvailable.get() && kotlinVersion >= Version(1, 8, 20) && kotlinVersion < Version(1, 9) && kotlinIncrementalUseClasspathSnapshot.orNull == null) {
                yield("The Kotlin plugin in version $kotlinVersion used with the Gradle IntelliJ Plugin leads to the 'java.lang.OutOfMemoryError: Java heap space' exception, see: https://jb.gg/intellij-platform-kotlin-oom")
            }
        }.joinToString("\n") { "- $it" }.takeIf(String::isNotEmpty)?.let {
            warn(
                context,
                listOf("The following plugin configuration issues were found:", it, "See: https://jb.gg/intellij-platform-versions").joinToString("\n")
            )
        }

        if (
            pluginVerifierDownloadPath != oldPluginVerifierDownloadPath
            && oldPluginVerifierDownloadPath.exists()
            && oldPluginVerifierDownloadPath.listFiles().isNotEmpty()
        ) {
            info(
                context,
                "The Plugin Verifier download directory is set to $pluginVerifierDownloadPath, but downloaded IDEs were also found in $oldPluginVerifierDownloadPath, see: https://jb.gg/intellij-platform-plugin-verifier-old-download-dir",
            )
        }
    }

    private fun getPlatformJavaVersion(buildNumber: Version) = PlatformJavaVersions.entries.firstOrNull { buildNumber >= it.key }?.value

    private fun getPlatformKotlinVersion(buildNumber: Version) = PlatformKotlinVersions.entries.firstOrNull { buildNumber >= it.key }?.value

    private operator fun JavaVersion?.compareTo(other: JavaVersion?) = other?.let { this?.compareTo(it) } ?: 0

    private operator fun Version?.compareTo(other: Version?) = other?.let { this?.compareTo(it) } ?: 0
}
