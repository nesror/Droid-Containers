package cn.yzapp.androidcontainer.core.billing

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.util.UUID

private val Context.entitlementDataStore by preferencesDataStore(
    name = "entitlements",
    // 缓存文件损坏时自愈为空（状态回落 Unknown → 门禁视为未解锁），而不是让收集方崩溃；
    // 下一次 refresh 会从 Play 重建缓存（远程控制门禁依赖该状态，见 m8_m9 §7）。
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * 权益缓存（DataStore `entitlements`，方案 §6.4）。
 *
 * 只存 `purchaseToken` 的 SHA-256 而非明文，仅用于判断「是否换过购买」；
 * 权益位落地在本机，因此**离线可用**。
 */
internal class EntitlementStore(private val context: Context) {

    private object Keys {
        val PRO_UNLOCKED = booleanPreferencesKey("pro_unlocked")
        val PURCHASE_TOKEN_HASH = stringPreferencesKey("purchase_token_hash")
        val LAST_VERIFIED_AT = longPreferencesKey("last_verified_at")
        val ACCOUNT_TAG = stringPreferencesKey("account_tag")
    }

    /** 上次确认的权益位；`null` = 从未查询过，对应 [EntitlementState.Unknown]。 */
    val proUnlocked: Flow<Boolean?> = context.entitlementDataStore.data.map { it[Keys.PRO_UNLOCKED] }

    /** 上次与 Play 校验的时间戳（节流 + 诊断），不做强制联网。 */
    val lastVerifiedAt: Flow<Long?> = context.entitlementDataStore.data.map { it[Keys.LAST_VERIFIED_AT] }

    /**
     * 落一次校验结论。[ownedToken] 为 `null` 表示 Play 明确回复「无购买」，
     * 此时回落为未解锁（退款 / 撤销的唯一本地感知手段）。
     */
    suspend fun markVerified(ownedToken: String?) {
        context.entitlementDataStore.edit { prefs ->
            prefs[Keys.LAST_VERIFIED_AT] = System.currentTimeMillis()
            if (ownedToken == null) {
                prefs[Keys.PRO_UNLOCKED] = false
                prefs.remove(Keys.PURCHASE_TOKEN_HASH)
            } else {
                prefs[Keys.PRO_UNLOCKED] = true
                prefs[Keys.PURCHASE_TOKEN_HASH] = sha256(ownedToken)
            }
        }
    }

    /** `obfuscatedAccountId` 用的随机 UUID，惰性生成并持久化（无账号体系时的对账标识，不含隐私数据）。 */
    suspend fun ensureAccountTag(): String {
        readAccountTag()?.let { return it }
        val tag = UUID.randomUUID().toString()
        context.entitlementDataStore.edit { prefs ->
            if (prefs[Keys.ACCOUNT_TAG].isNullOrBlank()) prefs[Keys.ACCOUNT_TAG] = tag
        }
        return readAccountTag() ?: tag
    }

    private suspend fun readAccountTag(): String? =
        context.entitlementDataStore.data.first()[Keys.ACCOUNT_TAG]?.takeIf { it.isNotBlank() }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
