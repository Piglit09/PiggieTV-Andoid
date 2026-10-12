pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

// Evaluate the source and operation before any project build script or task executes.
val ptvTasks = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
val ptvMode = when {
    ptvTasks.any { it.startsWith("publish", true) || it.startsWith("upload", true) } -> "Publish"
    ptvTasks.any {
        it.startsWith("install", true) || it.startsWith("uninstall", true) ||
            it.startsWith("connected", true) || it.startsWith("device", true)
    } -> "Install"
    ptvTasks.any {
        it.equals("build", true) || it.contains("Release", true) ||
            it.startsWith("bundle", true) || it.startsWith("package", true) ||
            it.equals("verifyPublicBetaRelease", true)
    } -> "Package"
    ptvTasks.isEmpty() || ptvTasks.all {
        it.matches(Regex("^(test.*|assemble.*Debug|compile.*|ksp.*|lint.*|detekt.*|check|clean|help|tasks|projects|properties|dependencies|dependencyInsight)$", RegexOption.IGNORE_CASE))
    } -> "Build"
    else -> "Package"
}
val ptvPreflight = file("scripts/ptv-mobile-workspace-preflight.ps1")
if (!ptvPreflight.isFile) {
    throw GradleException("PTV Mobile workspace preflight is missing.")
}
val ptvPowerShell = if (System.getProperty("os.name").startsWith("Windows", true)) "powershell.exe" else "pwsh"
val ptvCheck = ProcessBuilder(
    ptvPowerShell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
    "-File", ptvPreflight.absolutePath, "-Mode", ptvMode,
).directory(settingsDir).redirectErrorStream(true).start()
val ptvCheckOutput = ptvCheck.inputStream.bufferedReader().use { it.readText() }.trim()
if (ptvCheck.waitFor() != 0) {
    throw GradleException("PTV Mobile workspace preflight rejected $ptvMode: $ptvCheckOutput")
}
println(ptvCheckOutput)

include(":app")
