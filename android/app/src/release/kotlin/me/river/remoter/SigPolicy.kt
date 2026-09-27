package me.river.remoter

import me.river.remoter.core.crypto.BiometricAuthorizer
import me.river.remoter.core.crypto.KeySpecs
import me.river.remoter.core.crypto.PromptHost
import me.river.remoter.core.crypto.SigAuthorizer

object SigPolicy {
    val sigSpec = KeySpecs::sig
    fun authorizer(host: PromptHost): SigAuthorizer = BiometricAuthorizer(host)
}
