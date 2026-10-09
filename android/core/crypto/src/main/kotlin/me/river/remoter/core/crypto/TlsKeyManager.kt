package me.river.remoter.core.crypto

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

// ignores CA hints: the laptop pins the key itself
class TlsKeyManager(private val keys: Keys) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = TLS_ALIAS
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = TLS_ALIAS
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(TLS_ALIAS)
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == TLS_ALIAS && keys.has(TLS_ALIAS)) keys.chain(TLS_ALIAS).toTypedArray() else null
    override fun getPrivateKey(alias: String?): PrivateKey? =
        if (alias == TLS_ALIAS && keys.has(TLS_ALIAS)) keys.privateKey(TLS_ALIAS) else null

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
}
