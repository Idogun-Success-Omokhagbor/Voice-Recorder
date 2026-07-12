package org.fossify.voicerecorder.helpers.email

internal object EmailAddressValidator {
    private const val MAX_ADDRESS_LENGTH = 254
    private const val MAX_LOCAL_PART_LENGTH = 64
    private val localPartPattern = Regex("[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]+")
    private val domainLabelPattern = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")

    fun isValid(address: String): Boolean {
        val candidate = address.trim()
        if (candidate.isEmpty() || candidate.length > MAX_ADDRESS_LENGTH) {
            return false
        }

        val atIndex = candidate.indexOf('@')
        if (atIndex <= 0 || atIndex != candidate.lastIndexOf('@')) {
            return false
        }

        val localPart = candidate.substring(0, atIndex)
        val domain = candidate.substring(atIndex + 1)
        if (
            localPart.length > MAX_LOCAL_PART_LENGTH ||
            localPart.startsWith('.') ||
            localPart.endsWith('.') ||
            ".." in localPart ||
            !localPartPattern.matches(localPart)
        ) {
            return false
        }

        val labels = domain.split('.')
        return labels.size >= 2 && labels.all(domainLabelPattern::matches)
    }
}
