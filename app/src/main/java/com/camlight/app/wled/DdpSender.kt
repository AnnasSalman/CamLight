package com.camlight.app.wled

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Minimal DDP (UDP 4048) sender: 10-byte header + RGB data, split at 1440 bytes. */
class DdpSender(host: String, private val port: Int = PORT) : Closeable {
    private val socket = DatagramSocket()
    private val address = InetAddress.getByName(host)
    private var seq = 0
    private val buf = ByteArray(HEADER + MAX_DATA)

    /** Sends bytes [from, to) of [rgb]; the DDP offset carries the start so a partial range lands in the right LEDs. */
    fun send(rgb: ByteArray, from: Int = 0, to: Int = rgb.size) {
        var offset = from
        do {
            val len = minOf(MAX_DATA, to - offset)
            val last = offset + len >= to
            seq = seq % 15 + 1
            writeHeader(buf, offset, len, last, seq)
            System.arraycopy(rgb, offset, buf, HEADER, len)
            socket.send(DatagramPacket(buf, HEADER + len, address, port))
            offset += len
        } while (offset < to)
    }

    override fun close() = socket.close()

    companion object {
        const val PORT = 4048
        const val HEADER = 10
        const val MAX_DATA = 1440

        fun writeHeader(buf: ByteArray, offset: Int, len: Int, push: Boolean, seq: Int) {
            buf[0] = (0x40 or if (push) 0x01 else 0x00).toByte() // version 1, PUSH on last packet
            buf[1] = seq.toByte()
            buf[2] = 0x00 // default data type (RGB)
            buf[3] = 0x01 // default output device
            buf[4] = (offset shr 24).toByte()
            buf[5] = (offset shr 16).toByte()
            buf[6] = (offset shr 8).toByte()
            buf[7] = offset.toByte()
            buf[8] = (len shr 8).toByte()
            buf[9] = len.toByte()
        }
    }
}
