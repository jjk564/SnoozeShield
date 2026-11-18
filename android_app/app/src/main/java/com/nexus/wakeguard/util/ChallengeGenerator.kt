package com.nexus.wakeguard.util

import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ChallengeGenerator — Offline + LAN Synchronized 6-Digit Computer Challenge Engine.
 *
 * Uses HMAC-SHA256 over 60-second epochs matching `pc_bridge/server.py` (`compute_offline_totp`).
 * - Even if home Wi-Fi drops overnight, the Android app and the PC both compute the exact same
 *   6-digit Challenge Code and 6-digit Offline Unlock Confirmation Token!
 */
object ChallengeGenerator {
    private const val DEFAULT_SHARED_SECRET = "NX-WAKEGUARD-2026-MASTER-KEY"
    private const val EPOCH_INTERVAL_SECONDS = 60L

    data class ChallengePair(
        val epoch: Long,
        val pcChallengeCode: String,
        val phoneOfflineUnlockCode: String,
        val secondsRemaining: Int
    )

    fun computeCurrentChallenge(
        secretKey: String = DEFAULT_SHARED_SECRET,
        unixSeconds: Long = System.currentTimeMillis() / 1000L
    ): ChallengePair {
        val epoch = unixSeconds / EPOCH_INTERVAL_SECONDS
        val secondsRemaining = (EPOCH_INTERVAL_SECONDS - (unixSeconds % EPOCH_INTERVAL_SECONDS)).toInt()

        val pcCode = hmacSixDigit(secretKey, "WAKEGUARD-CHALLENGE-$epoch")
        val ackCode = hmacSixDigit(secretKey, "WAKEGUARD-UNLOCK-ACK-$epoch-$pcCode")

        return ChallengePair(
            epoch = epoch,
            pcChallengeCode = pcCode,
            phoneOfflineUnlockCode = ackCode,
            secondsRemaining = secondsRemaining
        )
    }

    fun verifyOfflineUnlockToken(
        userEnteredToken: String,
        secretKey: String = DEFAULT_SHARED_SECRET
    ): Boolean {
        val clean = userEnteredToken.trim()
        if (clean.length != 6) return false
        val nowSec = System.currentTimeMillis() / 1000L
        val current = computeCurrentChallenge(secretKey, nowSec)
        val previous = computeCurrentChallenge(secretKey, nowSec - EPOCH_INTERVAL_SECONDS)
        return clean == current.phoneOfflineUnlockCode || clean == previous.phoneOfflineUnlockCode
    }

    private fun hmacSixDigit(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(keySpec)
        val rawHmac = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        val hexFirst8 = rawHmac.take(4).joinToString("") { String.format(Locale.US, "%02x", it) }
        val num = (hexFirst8.toLong(16) % 1_000_000L).toInt()
        return String.format(Locale.US, "%06d", num)
    }
}
