package org.tgbackup.app.tg

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tgbackup.app.data.Account
import org.tgbackup.app.engine.LogBus
import java.io.File

/** Keeps exactly one live [AccountClient] per configured account. */
class AccountManager(private val rootDir: File, private val log: LogBus) {

    private val _clients = MutableStateFlow<Map<String, AccountClient>>(emptyMap())
    val clients: StateFlow<Map<String, AccountClient>> = _clients.asStateFlow()
    private val mutex = Mutex()

    private fun dirFor(id: String) = File(rootDir, "tdlib/$id")

    operator fun get(id: String): AccountClient? = _clients.value[id]

    /** Creates clients for new accounts, recreates ones whose API credentials changed, drops removed ones. */
    suspend fun sync(accounts: List<Account>) = mutex.withLock {
        val current = _clients.value.toMutableMap()
        val wanted = accounts.filter { it.apiId != 0 && it.apiHash.isNotBlank() }.associateBy { it.id }

        for ((id, client) in current.toMap()) {
            val acc = wanted[id]
            val credentialsChanged = acc != null &&
                (acc.apiId != client.account.apiId || acc.apiHash != client.account.apiHash || acc.phone != client.account.phone)
            if (acc == null || credentialsChanged) {
                client.close()
                current.remove(id)
            } else if (acc != client.account) {
                // Only the display name changed — keep the live session.
                client.account = acc
            }
        }
        for ((id, acc) in wanted) {
            if (id !in current) {
                current[id] = AccountClient(acc, dirFor(id), log).also { it.start() }
            }
        }
        _clients.value = current
    }

    /** Logs the account out, wipes its local TDLib data and starts a fresh client ready for a new login. */
    suspend fun logout(id: String) = mutex.withLock {
        val client = _clients.value[id] ?: return@withLock
        client.logOutAndClose()
        client.dispose()
        dirFor(id).deleteRecursively()
        val fresh = AccountClient(client.account, dirFor(id), log).also { it.start() }
        _clients.value = _clients.value + (id to fresh)
    }

    /** Removes on-disk data for an account that was deleted from the config. */
    suspend fun purge(id: String) = mutex.withLock {
        _clients.value[id]?.let { it.logOutAndClose(); it.dispose() }
        _clients.value = _clients.value - id
        dirFor(id).deleteRecursively()
    }
}
