package dev.ryan.playerlist

object PlayerListLinks {
    const val githubLatestReleaseApi = "https://api.github.com/repos/Vyriv/Skylist/releases/latest"
    const val githubLatestReleaseUrl = "https://github.com/Vyriv/Skylist/releases/latest"
    const val githubReleasesUrl = "https://github.com/Vyriv/Skylist/releases"
    // Remaining dynamic relay calls still use the Worker for presence sync and
    // Hypixel-linked Discord reads until those paths are migrated separately.
    const val skylistApiBaseUrl = "https://plain-dawn-a5d2.ryaneagers2015.workers.dev"
    const val skylistCosmeticsDataUrl = "https://api.vyriv.dev/v1/cosmetics"
    const val skylistScammerApiBaseUrl = "https://api.vyriv.dev"
}
