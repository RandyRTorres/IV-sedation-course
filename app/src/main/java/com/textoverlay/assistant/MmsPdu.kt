package com.textoverlay.assistant

import java.io.ByteArrayOutputStream

/**
 * Minimal encoder for an MMS M-Send.req PDU (OMA MMS Encapsulation + WSP),
 * supporting a text part and/or a single image part. This is what
 * SmsManager.sendMultimediaMessage transmits.
 */
object MmsPdu {

    // MMS header field assignments (high bit set)
    private const val MESSAGE_TYPE = 0x8C
    private const val TRANSACTION_ID = 0x98
    private const val MMS_VERSION = 0x8D
    private const val FROM = 0x89
    private const val TO = 0x97
    private const val CONTENT_TYPE = 0x84

    private const val M_SEND_REQ = 0x80
    private const val INSERT_ADDRESS_TOKEN = 0x81
    private const val VERSION_1_2 = 0x92
    private const val MULTIPART_MIXED = 0xA3 // 0x23 | 0x80

    /** Build the PDU. [imageMime] is e.g. "image/jpeg" or "image/gif". */
    fun buildSendReq(to: String, text: String?, image: ByteArray?, imageMime: String): ByteArray {
        val out = ByteArrayOutputStream()

        out.write(MESSAGE_TYPE); out.write(M_SEND_REQ)
        out.write(TRANSACTION_ID); writeTextString(out, "T" + System.currentTimeMillis().toString(16))
        out.write(MMS_VERSION); out.write(VERSION_1_2)
        out.write(FROM); out.write(0x01); out.write(INSERT_ADDRESS_TOKEN)
        out.write(TO); writeTextString(out, "$to/TYPE=PLMN")
        out.write(CONTENT_TYPE); out.write(MULTIPART_MIXED)

        // Multipart body
        val parts = ArrayList<Pair<String, ByteArray>>()
        if (!text.isNullOrEmpty()) parts.add("text/plain" to text.toByteArray(Charsets.UTF_8))
        if (image != null) parts.add(imageMime to image)

        writeUintvar(out, parts.size.toLong())
        for ((mime, data) in parts) {
            val ct = ByteArrayOutputStream().also { writeTextString(it, mime) }.toByteArray()
            writeUintvar(out, ct.size.toLong())   // HeadersLen (content-type only)
            writeUintvar(out, data.size.toLong())  // DataLen
            out.write(ct)
            out.write(data)
        }
        return out.toByteArray()
    }

    /** Text-string: ASCII bytes terminated by NUL. */
    private fun writeTextString(out: ByteArrayOutputStream, s: String) {
        out.write(s.toByteArray(Charsets.US_ASCII))
        out.write(0x00)
    }

    /** Variable-length unsigned integer (WSP uintvar). */
    private fun writeUintvar(out: ByteArrayOutputStream, value: Long) {
        var v = value
        if (v == 0L) { out.write(0); return }
        val bytes = ArrayList<Int>()
        while (v > 0) { bytes.add((v and 0x7F).toInt()); v = v shr 7 }
        for (i in bytes.indices.reversed()) {
            var b = bytes[i]
            if (i != 0) b = b or 0x80 // continuation bit on all but the last
            out.write(b)
        }
    }
}
