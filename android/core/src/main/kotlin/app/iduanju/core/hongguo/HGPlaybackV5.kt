@file:OptIn(ExperimentalUnsignedTypes::class)

package app.iduanju.core.hongguo

import app.iduanju.core.util.Bytes
import app.iduanju.core.util.u
import java.net.URLEncoder
import java.util.Base64

internal object HGPlaybackV5 {
    private const val LICENSE = "646637794"
    private const val FIELD21 = 312L
    private val magic = HGPlayerSign.hexBytes("2d4b4fca49750d433fb5ae2c226dcc56")
    private val signKey = HGPlayerSign.hexBytes("b23c2a470a20d105dc3f8c4438f8272ed46d6c89ffdcdedd5108698d26e9489d")
    private val cipherKey = Bytes.md5(signKey.copyOfRange(0, 16))
    private val cipherIv = Bytes.md5(signKey.copyOfRange(16, 32))
    private val processStarted = System.currentTimeMillis()
    private val processNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(Bytes.random(18))
    private val hashRounds = uintArrayOf(96u, 1u, 3u, 4u, 0u, 6u, 5u, 2u, 0u, 7u, 7u)

    fun sign(values: Map<String, String>, body: ByteArray, now: Long): Pair<String, Map<String, String>> {
        val timestamp = (now / 1000).toUInt()
        val timestampBytes = HGPlayerSign.le32(timestamp)
        val digest = Bytes.md5(body)
        val queryPrefix = values.entries.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
        for (offset in 0..1023) {
            val query = "$queryPrefix&_rticket=${now + offset}"
            val queryHash = Medusa.sm3(query.toByteArray())
            val branch = HGPlayerSign.getIV(
                HGPlayerSign.getIV(HGPlayerSign.getIV(0x20230928u, queryHash), digest), timestampBytes,
            ) and 15u
            if (branch != 12u) continue
            val factor = HGPlayerSign.branch0F13(queryHash, digest, timestampBytes, timestamp, 0x9d4a97c2u, hashRounds, 0x20u)
            return query to mapOf(
                "x-khronos" to timestamp.toString(),
                "x-helios" to HGPlayerSign.helios(timestamp, randomWord(), LICENSE),
                "x-medusa" to medusa(values, queryHash, factor, timestamp, timestampBytes),
                "x-ss-stub" to Bytes.hex(digest).uppercase(),
            )
        }
        error("无法生成播放签名")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%7E", "~")

    private fun randomWord(): UInt = HGPlayerSign.rdLE32(Bytes.random(4), 0)

    private fun medusa(
        values: Map<String, String>, queryHash: ByteArray, factor: ByteArray, timestamp: UInt, timestampBytes: ByteArray,
    ): String = with(HGPlayerSign) {
        val deviceId = values.getValue("device_id")
        val deviceType = values.getValue("device_type")
        val version = values.getValue("version_name")
        val flags = ByteArray(8)
        val deviceInfo = pSInt(1u, 1) + pSInt(2u, 2) + pStr(3u, appID) + pStr(4u, deviceId) +
            pStr(5u, processNonce) + pStr(6u, "!noperm!") + pStr(12u, "Asia/Shanghai,8") + pStr(13u, "zh_CN") +
            pStr(22u, values.getValue("os_version")) + pSInt(25u, processStarted) +
            pStr(30u, deviceType) + pStr(31u, "HONOR") + pStr(32u, deviceType) + pStr(34u, "HONOR")
        val env = pStr(6u, "v04.10.03.04-bugfix") + pStr(8u, values.getValue("channel")) +
            pBytes(12u, deviceInfo) + pStr(14u, version)
        val counters = pSInt(1u, 1) + pSInt(2u, 1) + pSInt(3u, 1) + pSInt(5u, 1)
        val message = pBytes(1u, magic) + pSInt(2u, 5) + pSInt(3u, randomWord().toLong()) +
            pStr(4u, appID) + pStr(5u, deviceId) + pStr(6u, LICENSE) + pStr(7u, version) +
            pStr(8u, "v04.10.03-ml-android") + pSInt(9u, 0x040a0300) + pBytes(10u, flags) +
            pSInt(12u, timestamp.toLong()) + pBytes(13u, factor) + pBytes(14u, queryHash.copyOf(6)) +
            pBytes(15u, counters) + pSInt(17u, timestamp.toLong()) + pStr(20u, "none") +
            pSInt(21u, FIELD21) + pBytes(23u, env) +
            pStr(24u, """{"cmr":16777216,"cmr2":16777216,"un_h":11,"vpn":0,"kd":0,"dyn":"","do":1,"tk":true}""")
        val nonce = randomWord()
        val (hash, seed) = keyHash(signKey, nonce)
        val transformed = (flags + xmxor(message, hash)).reversedArray()
        for (index in transformed.indices) {
            transformed[index] = (transformed.u(index) xor seed.u(index.inv() and 3)).toByte()
        }
        val check = 0x18000001u or ((queryHash.u(0).toUInt() and 63u) shl 14) or ((factor.u(0).toUInt() and 63u) shl 8)
        val packed = byteArrayOf(0xca.toByte()) + le32(randomWord()) + le32(check) + transformed +
            byteArrayOf((nonce shr 16).toByte(), (nonce shr 24).toByte())
        val prefix = xorBytes(le32(5u) + magic, timestampBytes)
        val envelope = byteArrayOf(nonce.toByte(), (nonce shr 8).toByte(), 0, 1)
        Bytes.b64(prefix + envelope + encrypt(packed, timestamp))
    }

    private val columns = arrayOf(
        intArrayOf(2, 0, 3, 1), intArrayOf(1, 3, 2, 0), intArrayOf(3, 2, 1, 0), intArrayOf(2, 3, 1, 0),
    )
    private val orders = arrayOf(
        intArrayOf(0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11),
        intArrayOf(0, 9, 14, 11, 4, 13, 2, 3, 8, 1, 6, 15, 12, 5, 10, 7),
        intArrayOf(0, 9, 14, 3, 4, 13, 2, 15, 8, 1, 6, 11, 12, 5, 10, 7),
        intArrayOf(0, 9, 14, 15, 4, 13, 2, 3, 8, 1, 6, 11, 12, 5, 10, 7),
    )
    private val initialWords = uintArrayOf(0xbbb5de72u, 0xd4638d5bu, 0x92a5f73bu, 0x89f8a1c9u)
    private val wireBits = intArrayOf(6, 5, 3, 4, 2, 7, 1, 0)
    private val compactBits = intArrayOf(1, 7, 3, 6, 2, 0, 4, 5)

    private fun encrypt(source: ByteArray, timestamp: UInt): ByteArray = with(HGPlayerSign) {
        require(source.size >= 248)
        val word = (timestamp and 3u).toInt()
        val column = columns[word]
        val order = orders[word]
        val boxOffset = word * 256
        fun substitute(value: Int) = sbox.u(boxOffset + value)

        val initial = initialWords[word]
        val initialBytes = le32(initial)
        val keys = xorBytes(initialBytes + initialBytes + initialBytes + initialBytes, cipherKey) + ByteArray(32)
        for (index in 4 until 12) {
            val start = 4 * index
            val previous = IntArray(4) { keys.u(start - 4 + it) }
            if (index % 4 == 0) {
                val shift = (index * 2) and 24
                val rotated = IntArray(4) { substitute(previous[(it + 1) and 3]) }
                rotated[0] = rotated[0] xor ((initial shr shift).toInt() and 255)
                rotated.copyInto(previous)
            }
            for (indexInWord in 0..3) {
                keys[start + indexInWord] = (previous[indexInWord] xor keys.u(start - 16 + indexInWord)).toByte()
            }
        }

        fun xtime(value: Int): Int = ((value shl 1) xor if (value and 128 != 0) 0x1b else 0) and 255
        fun block(input: ByteArray): ByteArray {
            var state = IntArray(16) { input.u(it) xor keys.u((it and 12) + column[it and 3]) }
            for (round in 1..2) {
                val substituted = IntArray(16) { substitute(state[column[it / 4] * 4 + (it and 3)]) }
                state = IntArray(16) { substituted[order[it]] }
                if (round == 1) {
                    val permuted = IntArray(16) { state[(it and 12) + column[it and 3]] }
                    state = IntArray(16) { index ->
                        val inColumn = index and 3
                        val total = permuted[inColumn] xor permuted[4 + inColumn] xor permuted[8 + inColumn] xor permuted[12 + inColumn]
                        permuted[index] xor total xor xtime(permuted[index] xor permuted[(index + 4) and 15])
                    }
                }
                for (index in state.indices) state[index] = state[index] xor keys.u(round * 16 + (index and 12) + column[index and 3])
            }
            return ByteArray(16) { (state[it] xor keys.u(16 + it)).toByte() }
        }

        val plain = ByteArray(32)
        for (index in 0 until 248) {
            val bit = index and 7
            val value = (source.u(index) shr wireBits[bit]) and 1
            plain[index / 8] = (plain.u(index / 8) or (value shl compactBits[bit])).toByte()
        }
        plain[31] = 1
        val first = block(xorBytes(plain.copyOfRange(0, 16), cipherIv))
        val encrypted = first + block(xorBytes(plain.copyOfRange(16, 32), first))
        val output = source.copyOf()
        for (index in 0 until 248) {
            val bit = index and 7
            val mask = 1 shl wireBits[bit]
            val value = (encrypted.u(index / 8) shr compactBits[bit]) and 1
            output[index] = ((source.u(index) and mask.inv()) or (value shl wireBits[bit])).toByte()
        }
        byteArrayOf(encrypted.last()) + output
    }

    private val sbox = HGPlayerSign.hexBytes(
        "53290fe9e51f316f90f74a7e034d367784ab49237cd0135cdc632c322fd3be0e2624a417ecd9151a7ff64f60730a87f9445ec6ada2f865d22b696c4188f24c0d" +
        "f37b75510b939f6bc2fe6883e68bc8c722181ba0bdb08c9871b50c9a16b46eea8eed20ba4b8a14e0fcf1468dbbef05a93e5a9d7dcf06898635c1fd99f5de287a" +
        "80c594daaab19b6279fbd75070cb52eb2555c456e39e7839853874e2f0013ac0b64742d8ca10e4b79c955de8b843ce4ed464a6a72ed508091c92e7b3eedd1e1d" +
        "d12afac321618fa3d65f9167c982f43d66a53c6a1997585b346dbf9672ff37cdbc073b2dae11453304df00815476b2a13fccdb30b957a8acafe1274002485912" +
        "38922563e36406d37a24100b7902a028044e218453c2a399199c9d13dc40c55ba67e502273850ebdc1a2e2188b8f2d9e3cb909b8c46a2a9f4a6dd93007eafa65" +
        "b7b3a881aa908cc74f0ad76bbbb58617c37012ca05a469fd44e62eecf1e4272ce860c0ae5eadf99b20d8f0234174006f3f110d7657d01645d4eff8f71471cbfe" +
        "1cb2f52648727bf3c9de55153e031b4d61757db63dbf7782eeafe9018a8891362fb14759ac32bab03b349aeb5a377c5156be4c8dfbe13a684bda39cf836697c8" +
        "94545fe0f2491acea9fc6252f4ff1e0fe72bd2df6e67a7f635e5bcc6d1a5dd951d89960887a18eb40c785cdb1f80ed42299333ab98cdd67f316cd54346585dcc" +
        "d759fb722789b08807cc0fda293f7e5e1819bb0353517020b31d3d00374952bea2c421e862040a9ec892a05b266b7a0d0cc316f483b81af39715657ffe32ad8d" +
        "e0c13077646035236ee5363e5f900e9a7cf850a81cd9960b6df73317226f99eaa6fff15cec34acb98246639b918ab2851466a7cdfc4ad1b52c2ae95a58d3734e" +
        "47ddd5b7560193f644ef9c408b7dee39b4d6aabf4de761e3252d4c7ba3843187745d55e6a5db05d42b45caed43c0c5d0b64bfa9d94a4a9d8f2f0e1a108dcfd28" +
        "8fd2bc1fc7bd675412138169cb09249875ba3b68cfb11180afae104fc9789571c6dfce02413886796ae21e3c488cc21b9f423a066c2eabf98e2fdeeb5776e4f5" +
        "d09a515ba58cbbab3e37b09fd4f6d9244df80512e84cd7956be301cf64f1429d58577cc241f3a2c655144f81aaf95a4e108da894b38970561ae9002619fbbac9" +
        "1fa922ed202bfc62cb08e0de2a7f3631435f8b47c8a3c563b4db4071ce74c46da6fa696a9e282d1e825d663b93bfd56c48dce1884bb10fccea063929f746443f" +
        "d830a4232e53da21a1f2d1bdfebc3a02c10a1b8ad31d04fd616590d272ac737e98eb33af60528716bee78ec0ee8699031177277d9cff32542c67caf5f4459168" +
        "1335ae0e178f3409a7803db83892dde5b9b7f0c750e24aef077badd678a09b850b84ec1597e4963cb5e675df1c0d6fb618496ecd76c35c798325590c2fb27a5e",
    )
}
