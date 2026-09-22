package com.tencent.kuikly.core.render.android.expand.module

import android.util.Base64
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 基于文件的 known_hosts 存储 + TOFU 校验（Android）。
 *
 * 为什么需要：此前 `StrictHostKeyChecking=no` 表示**无条件接受任何主机公钥**，
 * 攻击者可以中间人冒充服务器窃取口令与文件（安全审计 P0）。
 *
 * 策略（与 Web 网关语义一致）：
 * - `TOFU`（默认）：首次见到某主机指纹 → 记录并放行；已记录但不一致 → 拒绝（CHANGED）
 * - `STRICT`：未记录也拒绝（必须先用其它方式信任）
 * - `INSECURE`：不校验（仅本地调试，显式选择）
 *
 * 存储：`<filesDir>/sftp_known_hosts.json`，形如 `{ "host:port": "SHA256:..." }`。
 */
class KnownHostsRepository(
    private val file: File,
    private val policy: String = POLICY_TOFU,
) : HostKeyRepository {

    override fun check(host: String, key: ByteArray?): Int {
        if (policy == POLICY_INSECURE) return HostKeyRepository.OK
        if (key == null || key.isEmpty()) return HostKeyRepository.NOT_INCLUDED
        val fp = fingerprint(key)
        val map = load()
        val known = map.optString(host, "")
        return when {
            known.isEmpty() && policy == POLICY_STRICT -> HostKeyRepository.NOT_INCLUDED
            known.isEmpty() -> {
                map.put(host, fp)          // 首次信任（TOFU）
                save(map)
                HostKeyRepository.OK
            }
            known == fp -> HostKeyRepository.OK
            else -> HostKeyRepository.CHANGED // 指纹变化 → JSch 抛异常 → 错误码 1004
        }
    }

    /** 已记录主机的指纹（host 形如 "example.com:22"）；未记录返回空串。 */
    fun fingerprintFor(host: String): String = load().optString(host, "")

    /** 供 UI：列出已信任主机 */
    fun list(): List<Pair<String, String>> {
        val map = load()
        return map.keys().asSequence().map { it to map.optString(it, "") }.toList()
    }

    /** 供 UI：删除信任记录 */
    fun forget(host: String) {
        val map = load()
        map.remove(host)
        save(map)
    }

    /* ---- HostKeyRepository 其余方法（TOFU 在 check 内完成，无需额外写入）---- */

    override fun add(key: HostKey?, userinfo: UserInfo?) { /* no-op：由 check 完成 TOFU 落盘 */ }

    override fun remove(host: String?, type: String?) { /* no-op */ }

    override fun remove(host: String?, type: String?, key: ByteArray?) { /* no-op */ }

    override fun getKnownHostsRepositoryID(): String = "kuikly-sftp-known-hosts"

    override fun getHostKey(): Array<HostKey> = emptyArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

    /* ---- 内部 ---- */

    private fun load(): JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }

    private fun save(map: JSONObject) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(map.toString())
        } catch (e: Exception) {
            // 落盘失败不应阻断连接（但下次仍需重新 TOFU）
        }
    }

    private fun fingerprint(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP).trimEnd('=')
    }

    companion object {
        const val POLICY_TOFU = "TOFU"
        const val POLICY_STRICT = "STRICT"
        const val POLICY_INSECURE = "INSECURE"
    }
}
