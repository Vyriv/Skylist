package dev.ryan.playerlist

object PlayerListLinks {
    const val githubLatestReleaseApi = "https://api.github.com/repos/Vyriv/Skylist/releases/latest"
    const val githubLatestReleaseUrl = "https://github.com/Vyriv/Skylist/releases/latest"
    const val githubReleasesUrl = "https://github.com/Vyriv/Skylist/releases"
    // Remaining dynamic API calls go through the Skylist-operated relay service because
    // the mod needs live scammer lookups, presence sync, and Hypixel-linked Discord reads.
    const val skylistApiBaseUrl = "https://plain-dawn-a5d2.ryaneagers2015.workers.dev"
    const val skylistCosmeticsDataUrl = "$skylistApiBaseUrl/cosmetics/people"
}
