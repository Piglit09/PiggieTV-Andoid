package org.jellyfin.mobile.ui.screens.home

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class NativeHomeAuthenticationSourceTest {
    @Test
    fun `view model delegates every authentication lifecycle operation to the controller`() {
        val source = sourceFile(VIEW_MODEL_SOURCE).toFile().readText()

        listOf(
            "apiClientController.authState",
            "apiClientController.restoreSession()",
            "apiClientController.authenticatePassword(",
            "apiClientController.authenticateWithQuickConnect(",
            "apiClientController.cancelQuickConnect()",
            "apiClientController.logoutCurrentUser()",
        ).forEach { required -> assertTrue(source.contains(required), required) }
        assertFalse(source.contains("authenticateUserByName"))
        assertFalse(source.contains("setupUser("))
        assertFalse(source.contains("loadSavedServerUser("))
    }

    @Test
    fun `Quick Connect renders controller state without saveable code state`() {
        val source = sourceFile(SCREEN_SOURCE).toFile().readText()

        assertTrue(source.contains("waiting.displayCode"))
        assertTrue(source.contains("onCancelQuickConnect"))
        assertFalse(Regex("rememberSaveable[^\\n]*(displayCode|quickConnectCode)").containsMatchIn(source))
    }

    @Test
    fun `leaving login ownership cancels Quick Connect and subflows cannot open during auth`() {
        val screenSource = sourceFile(SCREEN_SOURCE).toFile().readText()
        val viewModelSource = sourceFile(VIEW_MODEL_SOURCE).toFile().readText()

        assertTrue(screenSource.contains("viewModel.cancelQuickConnect()"))
        assertTrue(screenSource.contains("onCancelQuickConnect()"))
        assertTrue(screenSource.contains("enabled = !authenticationInProgress"))
        assertTrue(viewModelSource.contains("override fun onCleared()"))
        assertTrue(viewModelSource.contains("apiClientController.cancelQuickConnect()"))
    }

    private fun sourceFile(relativePath: String): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir"))
        val fromRoot = workingDirectory.resolve("app").resolve(relativePath)
        if (Files.isRegularFile(fromRoot)) return fromRoot

        val fromApp = workingDirectory.resolve(relativePath)
        check(Files.isRegularFile(fromApp)) { "Source file was not found: $relativePath" }
        return fromApp
    }

    private companion object {
        const val VIEW_MODEL_SOURCE =
            "src/main/java/org/jellyfin/mobile/ui/screens/home/NativeHomeViewModel.kt"
        const val SCREEN_SOURCE =
            "src/main/java/org/jellyfin/mobile/ui/screens/home/NativeHomeScreen.kt"
    }
}
