package com.blufox.vpn

/**
 * Compatibility stub for the retired first-generation screen.
 * BluFox no longer fetches configuration lists from CloudFox or depends on its repository.
 */
data class FreeConfigProfile(
    val name: String,
    val protocol: String,
    val shareLink: String,
    val configJson: String,
    val source: String
)

@Deprecated("CloudFox sources are intentionally not used by BluFox.")
object CloudFoxFreeConfigRepository {
    fun fetch(): List<FreeConfigProfile> = emptyList()
}
