package com.saworks.pblock.core

internal fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xff
internal fun u16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)
internal fun put16(b: ByteArray, i: Int, v: Int) {
    b[i] = (v ushr 8).toByte()
    b[i + 1] = v.toByte()
}
