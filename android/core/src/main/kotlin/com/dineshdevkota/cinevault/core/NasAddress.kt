package com.dineshdevkota.cinevault.core

/** A validated server address, independent of share names and credentials. */
data class NasAddress(val host: String, val port: Int?) {
    val authority: String get() = (if (':' in host) "[${host.replace("%", "%25")}]" else host) +
        (port?.let { ":$it" } ?: "")

    companion object {
        private const val INVALID_ADDRESS = "Enter a valid NAS hostname or IP address. Enter credentials in the sign-in fields."
        private const val INVALID_PORT = "Port must be a number from 1 to 65535."

        /** Accepts a hostname/IP, SMB URL or Windows network path. An explicit port takes priority. */
        fun parse(raw: String, port: Int? = null): NasAddress {
            require(port == null || port in 1..65535) { INVALID_PORT }
            var text = raw.trim()
            val isUrl = text.startsWith("smb://", ignoreCase = true)
            if (isUrl) text = text.drop(6)
            require("://" !in text && '?' !in text && '#' !in text) { INVALID_ADDRESS }
            text = text.replace('\\', '/').removePrefix("//")
            val authority = text.substringBefore('/')
            require(authority.isNotEmpty() && authority.none { it.isWhitespace() || it == '@' }) { INVALID_ADDRESS }
            var host: String
            var embeddedPort: Int? = null
            fun parsePort(value: String): Int {
                require(value.isNotEmpty() && value.all { it in '0'..'9' }) { INVALID_PORT }
                return value.toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: throw IllegalArgumentException(INVALID_PORT)
            }
            if (authority.startsWith('[')) {
                val end = authority.indexOf(']')
                require(end > 1) { INVALID_ADDRESS }
                host = authority.substring(1, end)
                val suffix = authority.substring(end + 1)
                require(suffix.isEmpty() || suffix.startsWith(':')) { INVALID_ADDRESS }
                if (suffix.isNotEmpty()) embeddedPort = parsePort(suffix.drop(1))
                require(':' in host) { INVALID_ADDRESS }
            } else if (authority.count { it == ':' } == 1) {
                host = authority.substringBefore(':')
                embeddedPort = parsePort(authority.substringAfter(':'))
            } else host = authority
            if (isUrl) host = host.replace("%25", "%")
            require(if (':' in host) validIPv6(host) else validHostname(host)) { INVALID_ADDRESS }
            return NasAddress(host, port ?: embeddedPort)
        }

        private fun validHostname(host: String): Boolean {
            val labels = host.removeSuffix(".").split('.')
            if ('.' in host && host.all { it in "0123456789." }) return validIPv4(host)
            return host.length in 1..253 && labels.all { label ->
                label.isNotEmpty() && label.length <= 63 && label.first() != '-' && label.last() != '-' &&
                    label.all { it.isLetterOrDigit() || it == '-' || it == '_' }
            }
        }

        private fun validIPv4(address: String): Boolean {
            val octets = address.split('.')
            return octets.size == 4 && octets.all {
                it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } &&
                    it.toIntOrNull()?.let { value -> value in 0..255 } == true
            }
        }

        private fun validIPv6(host: String): Boolean {
            val address = host.substringBefore('%')
            if ('%' in host) {
                val scope = host.substringAfter('%')
                if (scope.isEmpty() || scope.any { !it.isLetterOrDigit() && it !in "_.-" }) return false
            }
            if (":::" in address || address.indexOf("::") != address.lastIndexOf("::")) return false
            val compressed = "::" in address
            if (address.startsWith(':') && !address.startsWith("::") ||
                address.endsWith(':') && !address.endsWith("::")) return false
            val groups = address.split(':').filter { it.isNotEmpty() }
            var units = 0
            for ((index, group) in groups.withIndex()) {
                if ('.' in group) {
                    if (index != groups.lastIndex || !address.endsWith(group) || !validIPv4(group)) return false
                    units += 2
                } else {
                    if (group.length !in 1..4 || group.any { it !in "0123456789abcdefABCDEF" }) return false
                    units++
                }
            }
            return if (compressed) units < 8 else units == 8
        }
    }
}
