package me.river.remoter.core.net

/** The same name rules the laptop enforces, so the form can say so before sending. */
object Names {
    private fun isLead(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_'

    fun isValidFolderName(name: String): Boolean {
        if (name.isEmpty() || name.length > 100 || name == "." || name == "..") return false
        return isLead(name[0]) && name.drop(1).all { isLead(it) || it == '-' }
    }

    fun isValidSessionName(name: String): Boolean {
        val n = name.trim(' ')
        if (n.isEmpty() || n.length > 48) return false
        return isLead(n[0]) && n.drop(1).all { isLead(it) || it == '-' || it == ' ' }
    }

    fun sessionNameFromFolder(folder: String): String {
        val mapped = buildString {
            var i = 0
            while (i < folder.length) {
                val cp = folder.codePointAt(i)
                val ch = cp.toChar()
                append(if (cp < 128 && (isLead(ch) || ch == '-' || ch == ' ')) ch else '-')
                i += Character.charCount(cp)
            }
        }
        val out = mapped.trimStart('-', ' ').take(48).trimEnd(' ')
        return out.ifEmpty { "session" }
    }

    /**
     * A conversation title as a session name. Titles are prose ("Fix: the banner's overlap"), so
     * what the rules refuse becomes a space rather than a dash, and runs of spaces fold into one.
     */
    fun sessionNameFromTitle(title: String): String {
        val mapped = buildString {
            var i = 0
            while (i < title.length) {
                val cp = title.codePointAt(i)
                val ch = cp.toChar()
                val keep = cp < 128 && (isLead(ch) || ch == '-')
                if (keep) append(ch) else if (isNotEmpty() && last() != ' ') append(' ')
                i += Character.charCount(cp)
            }
        }
        val out = mapped.trimStart('-', ' ').take(48).trimEnd(' ')
        return out.ifEmpty { "session" }
    }

    /** Raw name bytes that can't be shown faithfully, so nothing may act on them. */
    fun isUnsupportedName(raw: ByteArray): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val s = try {
            decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            return true
        }
        return s.codePoints().anyMatch { isHostile(it) }
    }

    private fun isHostile(u: Int): Boolean =
        u < 0x20 || u == 0x7f || u in 0x80..0x9f ||
            u in 0x202a..0x202e || u in 0x2066..0x2069 ||
            u == 0x200e || u == 0x200f || u == 0x061c ||
            u in 0x200b..0x200d || u == 0xfeff
}
