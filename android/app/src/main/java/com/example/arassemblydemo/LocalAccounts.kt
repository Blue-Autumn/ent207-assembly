package com.example.arassemblydemo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Local convenience authentication; no cloud identity, recovery, or production security claim. */
object PasswordHash {
    const val ITERATIONS = 120_000
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    data class Record(val salt: String, val digest: String, val iterations: Int = ITERATIONS)

    fun create(password: String): Record {
        require(password.length in 8..128)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Record(Base64.getEncoder().encodeToString(salt),
            Base64.getEncoder().encodeToString(derive(password, salt)))
    }

    fun valid(record: Record): Boolean = runCatching {
        record.iterations == ITERATIONS && record.salt.length <= 32 && record.digest.length <= 64 &&
            Base64.getDecoder().decode(record.salt).size == 16 && Base64.getDecoder().decode(record.digest).size == 32
    }.getOrDefault(false)

    fun verify(password: String, record: Record): Boolean {
        if (password.length !in 8..128 || !valid(record)) return false
        return runCatching {
            MessageDigest.isEqual(Base64.getDecoder().decode(record.digest),
                derive(password, Base64.getDecoder().decode(record.salt)))
        }.getOrDefault(false)
    }

    private fun derive(password: String, salt: ByteArray): ByteArray {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded }
        finally { spec.clearPassword(); chars.fill('\u0000') }
    }
}

data class Account(val id: String, val name: String)
data class AccountResult(val account: Account? = null, val error: String? = null) {
    val success: Boolean get() = account != null && error == null
}

class LocalAccounts(private val context: Context, private val namespace: String = "local-accounts-v1") {
    private val storageError get() = context.getString(R.string.acct_storage)
    private val prefs = context.applicationContext.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    @Volatile var lastError: String? = null
        private set
    private data class Entry(val account: Account, val key: String, val hash: PasswordHash.Record)
    private data class Store(val entries: List<Entry>, val currentId: String?)

    fun current(): Account? = synchronized(lock) {
        lastError = null
        try { val store = read(); store.entries.firstOrNull { it.account.id == store.currentId }?.account }
        catch (_: Exception) { lastError = storageError; null }
    }

    fun register(name: String, password: String): AccountResult = synchronized(lock) {
        lastError = null
        val display = name.trim()
        if (!validName(display)) return@synchronized failure(context.getString(R.string.acct_invalid_name))
        if (password.length < 8) return@synchronized failure(context.getString(R.string.auth_min_password))
        if (password.length > 64) return@synchronized failure(context.getString(R.string.acct_invalid_password))
        try {
            val store = read()
            val key = display.lowercase(Locale.ROOT)
            if (store.entries.any { it.key == key }) return@synchronized failure(context.getString(R.string.acct_taken))
            if (store.entries.size >= 20) return@synchronized failure(context.getString(R.string.acct_full))
            val account = Account(UUID.randomUUID().toString(), display)
            val entry = Entry(account, key, PasswordHash.create(password))
            write(Store(store.entries + entry, account.id))
            AccountResult(account)
        } catch (_: Exception) { failure(storageError) }
    }

    fun login(name: String, password: String): AccountResult = synchronized(lock) {
        lastError = null
        try {
            val store = read()
            val key = name.trim().lowercase(Locale.ROOT)
            val entry = store.entries.firstOrNull { it.key == key }
            if (entry == null || !PasswordHash.verify(password, entry.hash)) {
                // A failed login never leaves another account implicitly active.
                write(store.copy(currentId = null))
                return@synchronized failure(context.getString(R.string.acct_wrong))
            }
            write(store.copy(currentId = entry.account.id))
            AccountResult(entry.account)
        } catch (_: Exception) { failure(storageError) }
    }

    fun logout(): Boolean = synchronized(lock) {
        lastError = null
        try { write(read().copy(currentId = null)); true }
        catch (_: Exception) { lastError = storageError; false }
    }

    fun progressPrefsName(accountId: String, screenOnly: Boolean): String {
        require(UUID.fromString(accountId).toString() == accountId)
        // No legacy guest preference is read or migrated.
        return "${namespace}_progress_${accountId}_${if (screenOnly) "screen" else "physical"}"
    }

    private fun failure(message: String): AccountResult {
        lastError = message
        return AccountResult(error = message)
    }

    private fun read(): Store {
        check(namespace !in failedNamespaces)
        val raw = prefs.getString("store", null) ?: return Store(emptyList(), null)
        require(raw.length <= 50_000)
        val root = JSONObject(raw)
        require(root.getInt("schema") == 1)
        val array = root.getJSONArray("accounts")
        require(array.length() <= 20)
        fun JSONObject.string(key: String): String = (get(key) as? String) ?: error("Invalid string")
        val entries = (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val id = obj.string("id")
            require(UUID.fromString(id).toString() == id)
            val name = obj.string("name")
            require(validName(name) && name == name.trim())
            val key = obj.string("key")
            require(key == name.lowercase(Locale.ROOT))
            require(obj.string("algorithm") == "PBKDF2WithHmacSHA256")
            val hash = PasswordHash.Record(obj.string("salt"), obj.string("digest"), obj.getInt("iterations"))
            require(PasswordHash.valid(hash))
            Entry(Account(id, name), key, hash)
        }
        require(entries.map { it.key }.distinct().size == entries.size)
        require(entries.map { it.account.id }.distinct().size == entries.size)
        require(root.has("currentId"))
        val current = if (root.isNull("currentId")) null else root.string("currentId")
        require(current == null || entries.any { it.account.id == current })
        return Store(entries, current)
    }

    private fun write(store: Store) {
        val array = JSONArray()
        store.entries.forEach { entry -> array.put(JSONObject()
            .put("id", entry.account.id).put("name", entry.account.name).put("key", entry.key)
            .put("algorithm", "PBKDF2WithHmacSHA256").put("iterations", entry.hash.iterations)
            .put("salt", entry.hash.salt).put("digest", entry.hash.digest)) }
        val json = JSONObject().put("schema", 1).put("accounts", array)
            .put("currentId", store.currentId ?: JSONObject.NULL)
        if (!prefs.edit().putString("store", json.toString()).commit()) {
            // SharedPreferences can update memory before a failed disk write. Block this namespace.
            failedNamespaces.add(namespace)
            error("Storage commit failed")
        }
    }

    companion object {
        private val lock = Any()
        private val failedNamespaces = mutableSetOf<String>()

        private fun validName(name: String) = name.length in 2..24 && name.all {
            it.isLetterOrDigit() || it == '_' || it == '-'
        }
    }
}
