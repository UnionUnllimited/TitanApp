package com.titanvps.app.data

import com.titanvps.app.BuildConfig

/** Our User-Agent everywhere: "Titan(Android/<version>)" (iOS and Windows the same way). */
object UserAgent {
    val titan = "Titan(Android/${BuildConfig.VERSION_NAME})"
}
