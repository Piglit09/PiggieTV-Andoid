import org.gradle.api.GradleException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64

object SigningHelper {
    private val requiredEnvironmentVariables = listOf(
        "KEYSTORE",
        "KEYSTORE_PASSWORD",
        "KEY_ALIAS",
        "KEY_PASSWORD",
    )

    fun loadSigningConfig(): Config? {
        val environment = requiredEnvironmentVariables.associateWith(System::getenv)
        val configuredVariables = environment.filterValues { value -> !value.isNullOrBlank() }
        if (configuredVariables.isEmpty()) return null

        val missingVariables = environment
            .filterValues { value -> value.isNullOrBlank() }
            .keys
            .sorted()
        if (missingVariables.isNotEmpty()) {
            throw GradleException(
                "Incomplete public-beta signing configuration. Missing: ${missingVariables.joinToString()}.",
            )
        }

        val serializedKeystore = configuredVariables.getValue("KEYSTORE")!!
        val keystoreBytes = try {
            Base64.getDecoder().decode(serializedKeystore)
        } catch (exception: IllegalArgumentException) {
            throw GradleException("KEYSTORE must contain valid Base64-encoded keystore data.", exception)
        }
        if (keystoreBytes.isEmpty()) {
            throw GradleException("KEYSTORE must not decode to an empty file.")
        }

        val storeFile = writeTemporaryKeystore(keystoreBytes)

        return Config(
            storeFile,
            configuredVariables.getValue("KEYSTORE_PASSWORD")!!,
            configuredVariables.getValue("KEY_ALIAS")!!,
            configuredVariables.getValue("KEY_PASSWORD")!!,
        )
    }

    private fun writeTemporaryKeystore(bytes: ByteArray): File = try {
        Files.createTempFile("piggietv-signing-", ".jks")
            .toFile()
            .apply {
                writeBytes(bytes)
                deleteOnExit()
            }
    } catch (exception: IOException) {
        throw GradleException("Unable to prepare the temporary public-beta keystore.", exception)
    } catch (exception: SecurityException) {
        throw GradleException("Unable to prepare the temporary public-beta keystore.", exception)
    }

    data class Config(
        /**
         * Store file used when signing.
         */
        val storeFile: File,

        /**
         * Store password used when signing.
         */
        val storePassword: String,

        /**
         * Key alias used when signing.
         */
        val keyAlias: String,

        /**
         * Key password used when signing.
         */
        val keyPassword: String,
    )
}
