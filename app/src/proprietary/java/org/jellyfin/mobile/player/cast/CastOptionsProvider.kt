package org.jellyfin.mobile.player.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder().setReceiverApplicationId(applicationId.orEmpty()).build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    companion object {
        /**
         * ID of the cast receiver application.
         */
        private var applicationId: String? = null

        /**
         * Sets the application ID.
         *
         * @param appId the receiver application ID.
         */
        @JvmStatic
        fun setAppId(appId: String) {
            applicationId = appId
        }
    }
}
