package com.soywiz.korio.stream

import com.soywiz.korio.util.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.*

open class SyncStream {
	open fun read(buffer: ByteArray, offset: Int, len: Int): Int = throw UnsupportedOperationException()
	open fun write(buffer: ByteArray, offset: Int, len: Int): Unit = throw UnsupportedOperationException()
	open var position: Long
		set(value) = throw UnsupportedOperationException()
		get() = run { throw UnsupportedOperationException() }
	open var length: Long
		set(value) = throw UnsupportedOperationException()
		get() = run { throw UnsupportedOperationException() }
	val available: Long get() = length - position
	internal val temp = ByteArray(16)
}

inline fun <T> SyncStream.keepPosition(callback: () -> T): T {
	val old = this.position
	try {
		return callback()
	} finally {
		this.position = old
	}
}

class SliceSyncStream(val base: SyncStream, val baseOffset: Long, val baseEnd: Long) : SyncStream() {
	val baseLength: Long = baseEnd - baseOffset
	override var position: Long = 0L
	override var length: Long = baseLength

	override fun read(buffer: ByteArray, offset: Int, len: Int): Int {
		return base.keepPosition {
			base.position = this.baseOffset + position
			val res = base.read(buffer, offset, len)
			position += res
			res
		}
	}

	override fun write(buffer: ByteArray, offset: Int, len: Int) {
		return base.keepPosition {
			base.position = this.baseOffset + position
			base.write(buffer, offset, len)
			position += len
		}
	}
}

class FileSyncStream(val file: File, val mode: String = "r") : SyncStream() {
	val ra = RandomAccessFile(file, mode)

	override fun read(buffer: ByteArray, offset: Int, len: Int): Int {
		return ra.read(buffer, offset, len)
	}

	override fun write(buffer: ByteArray, offset: Int, len: Int) {
		ra.write(buffer, offset, len)
	}

	override var position: Long
		get() = ra.filePointer
		set(value) {
			ra.seek(value)
		}
	override var length: Long
		get() = ra.length()
		set(value) {
			ra.setLength(value)
		}
}

class MemorySyncStream(var data: ByteArray = ByteArray(0)) : SyncStream() {
	override var position: Long = 0L
	override var length: Long
		get() = data.size.toLong()
		set(value) {
			if (value != data.size.toLong()) data = Arrays.copyOf(data, value.toInt())
		}

	override fun read(buffer: ByteArray, offset: Int, len: Int): Int {
		val read = Math.min(len, available.toInt())
		System.arraycopy(this.data, this.position.toInt(), buffer, offset, read)
		this.position += read
		return read
	}

	override fun write(buffer: ByteArray, offset: Int, len: Int) {
		this.length = Math.max(this.position + len, this.length)
		System.arraycopy(buffer, offset, this.data, this.position.toInt(), len)
		this.position += len
	}

	fun toByteArray(): ByteArray = Arrays.copyOf(data, length.toInt())
}

fun SyncStream.slice(): SyncStream = SliceSyncStream(this, 0L, length)
fun SyncStream.sliceWithBounds(start: Long, end: Long): SyncStream = SliceSyncStream(this, start, end)
fun SyncStream.sliceWithSize(position: Long, length: Long): SyncStream = sliceWithBounds(position, position + length)

fun SyncStream.readSlice(length: Long): SyncStream = sliceWithSize(position, length).apply {
	this@readSlice.position += length
}

fun SyncStream.readStream(length: Long): SyncStream = readSlice(length)

fun SyncStream.readStringz(len: Int, charset: Charset = Charsets.UTF_8): String {
	val res = readBytes(len)
	val index = res.indexOf(0.toByte())
	return String(res, 0, if (index < 0) len else index, charset)
}

fun SyncStream.readString(len: Int, charset: Charset = Charsets.UTF_8): String = readBytes(len).toString(charset)
fun SyncStream.writeString(string: String, charset: Charset = Charsets.UTF_8): Unit = writeBytes(string.toByteArray(charset))

fun SyncStream.readExact(out: ByteArray, offset: Int, len: Int): Unit {
	var ooffset = offset
	var remaining = len
	while (remaining > 0) {
		val read = read(out, ooffset, remaining)
		if (read <= 0) throw RuntimeException("EOF")
		remaining -= read
		ooffset += read
	}
}

fun SyncStream.readBytesExact(len: Int): ByteArray = ByteArray(len).apply { readExact(this, 0, len) }

fun SyncStream.readBytes(len: Int): ByteArray = ByteArray(len).apply { read(this, 0, len) }
fun SyncStream.writeBytes(data: ByteArray): Unit = write(data, 0, data.size)

val SyncStream.eof: Boolean get () = this.available <= 0L
private fun SyncStream.readTemp(count: Int): ByteArray = temp.apply { read(temp, 0, count) }

fun SyncStream.readU8(): Int = readTemp(1).readU8(0)

fun SyncStream.readU16_le(): Int = readTemp(2).readU16_le(0)
fun SyncStream.readU32_le(): Long = readTemp(4).readU32_le(0)

fun SyncStream.readS16_le(): Int = readTemp(2).readS16_le(0)
fun SyncStream.readS32_le(): Int = readTemp(4).readS32_le(0)
fun SyncStream.readS64_le(): Long = readTemp(8).readS64_le(0)

fun SyncStream.readF32_le(): Float = readTemp(4).readF32_le(0)
fun SyncStream.readF64_le(): Double = readTemp(8).readF64_le(0)

fun SyncStream.readU16_be(): Int = readTemp(2).readU16_be(0)
fun SyncStream.readU32_be(): Long = readTemp(4).readU32_be(0)

fun SyncStream.readS16_be(): Int = readTemp(2).readS16_be(0)
fun SyncStream.readS32_be(): Int = readTemp(4).readS32_be(0)
fun SyncStream.readS64_be(): Long = readTemp(8).readS64_be(0)

fun SyncStream.readF32_be(): Float = readTemp(4).readF32_be(0)
fun SyncStream.readF64_be(): Double = readTemp(8).readF64_be(0)

fun SyncStream.readAvailable(): ByteArray = readBytes(available.toInt())
fun SyncStream.readAll(): ByteArray = readBytes(available.toInt())

private inline fun <T> SyncStream.readTypedArray(count: Int, elementSize: Int, crossinline gen: () -> T, crossinline read: (array: T, n: Int) -> Unit): T {
	val temp = readBytes(count * elementSize)
	val array = gen()
	for (n in 0 until count) read(array, n)
	return array
}

fun SyncStream.readUByteArray(count: Int): UByteArray = UByteArray(readBytes(count))

fun SyncStream.readShortArray_le(count: Int): ShortArray = readTypedArray(count, 2, { ShortArray(count) }, { array, n -> array[n] = temp.readS16_le(n * 2).toShort() })
fun SyncStream.readShortArray_be(count: Int): ShortArray = readTypedArray(count, 2, { ShortArray(count) }, { array, n -> array[n] = temp.readS16_be(n * 2).toShort() })

fun SyncStream.readCharArray_le(count: Int): CharArray = readTypedArray(count, 2, { CharArray(count) }, { array, n -> array[n] = temp.readU16_le(n * 2).toChar() })
fun SyncStream.readCharArray_be(count: Int): CharArray = readTypedArray(count, 2, { CharArray(count) }, { array, n -> array[n] = temp.readU16_be(n * 2).toChar() })

fun SyncStream.readIntArray_le(count: Int): IntArray = readTypedArray(count, 4, { IntArray(count) }, { array, n -> array[n] = temp.readS32_le(n * 4) })
fun SyncStream.readIntArray_be(count: Int): IntArray = readTypedArray(count, 4, { IntArray(count) }, { array, n -> array[n] = temp.readS32_be(n * 4) })

fun SyncStream.readLongArray_le(count: Int): LongArray = readTypedArray(count, 8, { LongArray(count) }, { array, n -> array[n] = temp.readS64_le(n * 8) })
fun SyncStream.readLongArray_be(count: Int): LongArray = readTypedArray(count, 8, { LongArray(count) }, { array, n -> array[n] = temp.readS64_be(n * 8) })

fun SyncStream.write8(v: Int): Unit = write(temp.apply { write8(0, v) }, 0, 1)

fun SyncStream.write16_le(v: Int): Unit = write(temp.apply { write16_le(0, v) }, 0, 2)
fun SyncStream.write32_le(v: Int): Unit = write(temp.apply { write32_le(0, v) }, 0, 4)
fun SyncStream.write64_le(v: Long): Unit = write(temp.apply { write64_le(0, v) }, 0, 8)
fun SyncStream.writeF32_le(v: Float): Unit = write(temp.apply { writeF32_le(0, v) }, 0, 4)
fun SyncStream.writeF64_le(v: Double): Unit = write(temp.apply { writeF64_le(0, v) }, 0, 8)

fun SyncStream.write16_be(v: Int): Unit = write(temp.apply { write16_be(0, v) }, 0, 2)
fun SyncStream.write32_be(v: Int): Unit = write(temp.apply { write32_be(0, v) }, 0, 4)
fun SyncStream.write64_be(v: Long): Unit = write(temp.apply { write64_be(0, v) }, 0, 8)
fun SyncStream.writeF32_be(v: Float): Unit = write(temp.apply { writeF32_be(0, v) }, 0, 4)
fun SyncStream.writeF64_be(v: Double): Unit = write(temp.apply { writeF64_be(0, v) }, 0, 8)

fun ByteArray.openSync(): MemorySyncStream = MemorySyncStream(this)
fun File.openSync(mode: String = "r"): FileSyncStream = FileSyncStream(this, mode)