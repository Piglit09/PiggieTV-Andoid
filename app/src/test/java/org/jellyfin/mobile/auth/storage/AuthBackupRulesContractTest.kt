package org.jellyfin.mobile.auth.storage

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AuthBackupRulesContractTest {
    @Test
    fun `manifest supplies backup rules for pre and post Android 12`() {
        val application = parse("app/src/main/AndroidManifest.xml")
            .getElementsByTagName("application")
            .item(0) as Element

        application.getAttributeNS(ANDROID_NAMESPACE, "fullBackupContent") shouldBe "@xml/backup_content"
        application.getAttributeNS(ANDROID_NAMESPACE, "dataExtractionRules") shouldBe "@xml/data_extraction_rules"
    }

    @Test
    fun `pre Android 12 allowlists only harmless preferences`() {
        val document = parse("app/src/main/res/xml/backup_content.xml")

        document.rules("exclude") shouldBe emptySet()
        document.rules("include") shouldBe SAFE_PREFERENCE_ALLOWLIST
    }

    @Test
    fun `cloud and device transfer share the same exact allowlist`() {
        val document = parse("app/src/main/res/xml/data_extraction_rules.xml")
        val cloudNode = document.getElementsByTagName("cloud-backup").item(0) as Element
        val deviceTransferNode = document.getElementsByTagName("device-transfer").item(0) as Element

        cloudNode.rules("exclude") shouldBe emptySet()
        deviceTransferNode.rules("exclude") shouldBe emptySet()
        cloudNode.rules("include") shouldBe SAFE_PREFERENCE_ALLOWLIST
        deviceTransferNode.rules("include") shouldBe SAFE_PREFERENCE_ALLOWLIST
    }

    private fun org.w3c.dom.Node.rules(elementName: String): Set<BackupRule> {
        val root = when (this) {
            is org.w3c.dom.Document -> documentElement
            is Element -> this
            else -> error("Unsupported XML node: ${javaClass.name}")
        }
        val nodes = root.getElementsByTagName(elementName)
        return buildSet {
            repeat(nodes.length) { index ->
                val element = nodes.item(index) as Element
                add(BackupRule(element.getAttribute("domain"), element.getAttribute("path")))
            }
        }
    }

    private fun parse(relativePath: String) = DocumentBuilderFactory.newInstance().run {
        isNamespaceAware = true
        newDocumentBuilder().parse(resolveRepositoryFile(relativePath))
    }

    private fun resolveRepositoryFile(relativePath: String): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        return generateSequence(start) { current -> current.parentFile }
            .map { root -> File(root, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Could not locate repository file: $relativePath")
    }

    private data class BackupRule(val domain: String, val path: String)

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        val SAFE_PREFERENCE_ALLOWLIST = setOf(
            BackupRule("sharedpref", "com.piggietv.android_preferences.xml"),
            BackupRule("sharedpref", "com.piggietv.android.debug_preferences.xml"),
            BackupRule("sharedpref", "org.jellyfin.mobile_preferences.xml"),
            BackupRule("sharedpref", "org.jellyfin.mobile.debug_preferences.xml"),
        )
    }
}
