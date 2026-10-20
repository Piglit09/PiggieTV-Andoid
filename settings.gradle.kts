pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

// Evaluate the source and operation before any project build script or task executes.
if (gradle.startParameter.isConfigurationCacheRequested) {
    throw GradleException("PTV Mobile workspace preflight requires settings evaluation on every invocation; configuration cache is disabled.")
}
val ptvTasks = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
val ptvMode = when {
    ptvTasks.any { it.startsWith("publish", true) || it.startsWith("upload", true) } -> "Publish"
    ptvTasks.any {
        it.startsWith("install", true) || it.startsWith("uninstall", true) ||
            it.startsWith("connected", true) || it.startsWith("device", true)
    } -> "Install"
    gradle.startParameter.taskNames.size == 1 && gradle.startParameter.taskNames[0] in listOf(
        ":app:assembleLibreDebug", ":app:assembleProprietaryDebug"
    ) -> "DebugPackage"
    ptvTasks.any {
        it.equals("build", true) || it.startsWith("assemble", true) ||
            it.contains("Release", true) || it.startsWith("bundle", true) || it.startsWith("package", true) ||
            it.equals("verifyPublicBetaRelease", true)
    } -> "Package"
    ptvTasks.isEmpty() || ptvTasks.all {
        it.matches(Regex("^(test.*|compile.*|ksp.*|lint.*|detekt.*|check|clean|help|tasks|projects|properties|dependencies|dependencyInsight)$", RegexOption.IGNORE_CASE))
    } -> "Build"
    else -> "Package"
}
val ptvBootstrapSystemProperties = mapOf(
    "file.encoding" to "UTF-8",
    "user.country" to "US",
    "user.language" to "en",
    "user.variant" to "",
)
if (ptvMode in listOf("Package", "DebugPackage") && (
        gradle.startParameter.projectProperties.isNotEmpty() ||
            gradle.startParameter.systemPropertiesArgs != ptvBootstrapSystemProperties ||
            gradle.startParameter.allInitScripts.isNotEmpty() ||
            gradle.startParameter.excludedTaskNames.isNotEmpty()
    )) {
    throw GradleException("Unapproved Gradle package arguments: project/system overrides, init scripts, and task exclusions are denied.")
}
if (ptvMode in listOf("Package", "DebugPackage")) {
    val sourceProperties = java.util.Properties()
    file("gradle.properties").inputStream().use { sourceProperties.load(it) }
    val sourceVersion = sourceProperties.getProperty("piggietv.version")?.trim()
    val effectiveVersion = providers.gradleProperty("piggietv.version").orNull
    if (sourceVersion.isNullOrEmpty() || effectiveVersion != sourceVersion) {
        throw GradleException("PTV Mobile package version differs from the approved source version.")
    }
}
val ptvPreflight = file("scripts/ptv-mobile-workspace-preflight.ps1")
if (!ptvPreflight.isFile) {
    throw GradleException("PTV Mobile workspace preflight is missing.")
}
val ptvPowerShell = if (System.getProperty("os.name").startsWith("Windows", true)) "powershell.exe" else "pwsh"
val ptvPreflightArgs = mutableListOf(
    ptvPowerShell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
    "-File", ptvPreflight.absolutePath, "-Mode", ptvMode,
)
if (ptvMode in listOf("Package", "DebugPackage")) {
    val sdkProperties = java.util.Properties()
    val localProperties = file("local.properties")
    if (localProperties.isFile) {
        localProperties.inputStream().use { sdkProperties.load(it) }
    }
    val sdkRoot = sdkProperties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: ""
    ptvPreflightArgs += listOf(
        "-GradleTaskList", gradle.startParameter.taskNames.joinToString("|"),
        "-JavaHome", System.getProperty("java.home") ?: "",
        "-AndroidSdkRoot", sdkRoot,
    )
}
val ptvCheck = ProcessBuilder(ptvPreflightArgs).directory(settingsDir).redirectErrorStream(true).start()
val ptvCheckOutput = ptvCheck.inputStream.bufferedReader().use { it.readText() }.trim()
if (ptvCheck.waitFor() != 0) {
    throw GradleException("PTV Mobile workspace preflight rejected $ptvMode: $ptvCheckOutput")
}
println(ptvCheckOutput)

include(":app")
