package dev.ryan.playerlist

internal object NameStyleMatcher {
    fun containsCandidate(
        text: String?,
        candidates: List<PlayerCustomizationRegistry.NameCandidate>,
    ): Boolean = !text.isNullOrEmpty() && candidates.isNotEmpty() && findFirstNameMatch(text, candidates) != null

    fun findFirstNameMatch(
        text: String,
        candidates: List<PlayerCustomizationRegistry.NameCandidate>,
        startIndex: Int = 0,
    ): MatchedCustomization? {
        var bestIndex = Int.MAX_VALUE
        var bestName: String? = null
        var bestCustomization: PlayerCustomizationRegistry.PlayerCustomization? = null

        candidates.forEach { candidate ->
            var searchIndex = startIndex
            while (searchIndex < text.length) {
                val candidateIndex = text.indexOf(candidate.text, searchIndex, ignoreCase = true)
                if (candidateIndex == -1) {
                    break
                }

                if (candidateIndex > bestIndex) {
                    break
                }

                if (!candidate.requiresBoundary || isNameBoundary(text, candidateIndex, candidateIndex + candidate.text.length)) {
                    if (candidateIndex < bestIndex || (candidateIndex == bestIndex && candidate.text.length > (bestName?.length ?: -1))) {
                        bestIndex = candidateIndex
                        bestName = candidate.text
                        bestCustomization = candidate.customization
                    }
                    break
                }

                searchIndex = candidateIndex + 1
            }
        }

        val matchedName = bestName ?: return null
        val customization = bestCustomization ?: return null
        return MatchedCustomization(NameMatch(bestIndex, matchedName), customization)
    }

    fun findNameMatch(
        text: String,
        customization: PlayerCustomizationRegistry.PlayerCustomization,
        startIndex: Int = 0,
        allowTruncatedPrefix: Boolean = false,
    ): NameMatch? {
        var bestIndex = Int.MAX_VALUE
        var bestName: String? = null
        customization.matchNames().forEach { candidateName ->
            val exactMatchIndex = text.indexOf(candidateName, startIndex, ignoreCase = true)
            if (exactMatchIndex != -1 &&
                (exactMatchIndex < bestIndex || (exactMatchIndex == bestIndex && (bestName == null || candidateName.length > bestName.length)))
            ) {
                bestIndex = exactMatchIndex
                bestName = candidateName
            }

            if (!allowTruncatedPrefix || candidateName.length < 8) {
                return@forEach
            }

            val minimumPrefixLength = maxOf(6, candidateName.length - 4, (candidateName.length * 0.7f).toInt())
            for (prefixLength in candidateName.length - 1 downTo minimumPrefixLength) {
                val candidatePrefix = candidateName.substring(0, prefixLength)
                var searchIndex = startIndex
                while (searchIndex < text.length) {
                    val prefixIndex = text.indexOf(candidatePrefix, searchIndex, ignoreCase = true)
                    if (prefixIndex == -1) {
                        break
                    }

                    if (isNameBoundary(text, prefixIndex, prefixIndex + candidatePrefix.length)) {
                        if (prefixIndex < bestIndex || (prefixIndex == bestIndex && (bestName == null || candidatePrefix.length > bestName.length))) {
                            bestIndex = prefixIndex
                            bestName = candidatePrefix
                        }
                    }

                    searchIndex = prefixIndex + 1
                }
            }
        }

        return bestName?.let { NameMatch(bestIndex, it) }
    }

    private fun isNameBoundary(text: String, start: Int, endExclusive: Int): Boolean {
        val characterBeforeMatch = text.getOrNull(start - 1)
        val characterAfterMatch = text.getOrNull(endExclusive)
        return isNameBoundaryCharacter(characterBeforeMatch) && isNameBoundaryCharacter(characterAfterMatch)
    }

    private fun isNameBoundaryCharacter(character: Char?): Boolean {
        if (character == null) {
            return true
        }
        return !character.isLetterOrDigit() && character != '_'
    }
}
