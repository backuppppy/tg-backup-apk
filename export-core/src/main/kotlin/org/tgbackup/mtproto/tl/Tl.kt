package org.tgbackup.mtproto.tl

import java.util.zip.GZIPInputStream

interface TlObject {
    val constructorId: Int
    fun serializeBody(w: TlWriter)
    fun serialize(w: TlWriter) {
        w.int(constructorId)
        serializeBody(w)
    }

    fun toBytes(): ByteArray = TlWriter().also { serialize(it) }.toByteArray()
}

interface TlFunction<R> : TlObject {
    fun parseResult(r: TlReader): R
}

class TlException(message: String) : RuntimeException(message)

object Tl {
    const val VECTOR = 0x1cb5c415
    const val BOOL_TRUE = 0x997275b5.toInt()
    const val BOOL_FALSE = 0xbc799737.toInt()
    const val GZIP_PACKED = 0x3072cfa1

    private val registry: HashMap<Int, (TlReader) -> TlObject> by lazy {
        HashMap<Int, (TlReader) -> TlObject>(4096).also { GenRegistry.fill(it) }
    }

    fun reader(id: Int): ((TlReader) -> TlObject)? = registry[id]

    fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(data.inputStream()).use { it.readBytes() }
}

class TlWriter {
    private var buf = ByteArray(256)
    var size = 0
        private set

    private fun ensure(n: Int) {
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
    }

    fun int(v: Int) {
        ensure(4)
        buf[size++] = v.toByte()
        buf[size++] = (v ushr 8).toByte()
        buf[size++] = (v ushr 16).toByte()
        buf[size++] = (v ushr 24).toByte()
    }

    fun long(v: Long) {
        int(v.toInt())
        int((v ushr 32).toInt())
    }

    fun double(v: Double) = long(java.lang.Double.doubleToRawLongBits(v))

    fun bool(v: Boolean) = int(if (v) Tl.BOOL_TRUE else Tl.BOOL_FALSE)

    fun raw(b: ByteArray) {
        ensure(b.size)
        System.arraycopy(b, 0, buf, size, b.size)
        size += b.size
    }

    fun bytes(b: ByteArray) {
        val len = b.size
        if (len <= 253) {
            ensure(1)
            buf[size++] = len.toByte()
            raw(b)
            pad(len + 1)
        } else {
            ensure(4)
            buf[size++] = 254.toByte()
            buf[size++] = len.toByte()
            buf[size++] = (len ushr 8).toByte()
            buf[size++] = (len ushr 16).toByte()
            raw(b)
            pad(len + 4)
        }
    }

    private fun pad(written: Int) {
        val p = (4 - written % 4) % 4
        ensure(p)
        repeat(p) { buf[size++] = 0 }
    }

    fun string(s: String) = bytes(s.toByteArray(Charsets.UTF_8))

    fun <T> vector(list: List<T>, item: (T) -> Unit) {
        int(Tl.VECTOR)
        bareVector(list, item)
    }

    fun <T> bareVector(list: List<T>, item: (T) -> Unit) {
        int(list.size)
        list.forEach(item)
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

class TlReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {

    val position: Int get() = pos
    val remaining: Int get() = end - pos

    private fun need(n: Int) {
        if (pos + n > end) throw TlException("TL buffer underflow at $pos (+$n of $end)")
    }

    fun int(): Int {
        need(4)
        val v = (buf[pos].toInt() and 0xff) or
            ((buf[pos + 1].toInt() and 0xff) shl 8) or
            ((buf[pos + 2].toInt() and 0xff) shl 16) or
            ((buf[pos + 3].toInt() and 0xff) shl 24)
        pos += 4
        return v
    }

    fun long(): Long {
        val lo = int().toLong() and 0xffffffffL
        val hi = int().toLong()
        return lo or (hi shl 32)
    }

    fun double(): Double = java.lang.Double.longBitsToDouble(long())

    fun bool(): Boolean = when (val id = int()) {
        Tl.BOOL_TRUE -> true
        Tl.BOOL_FALSE -> false
        else -> throw TlException("Expected Bool, got 0x%08x".format(id))
    }

    fun raw(n: Int): ByteArray {
        need(n)
        val r = buf.copyOfRange(pos, pos + n)
        pos += n
        return r
    }

    fun bytes(): ByteArray {
        need(1)
        val first = buf[pos].toInt() and 0xff
        val len: Int
        val header: Int
        if (first <= 253) {
            len = first
            header = 1
            pos += 1
        } else {
            need(4)
            len = (buf[pos + 1].toInt() and 0xff) or
                ((buf[pos + 2].toInt() and 0xff) shl 8) or
                ((buf[pos + 3].toInt() and 0xff) shl 16)
            header = 4
            pos += 4
        }
        val data = raw(len)
        val p = (4 - (len + header) % 4) % 4
        need(p)
        pos += p
        return data
    }

    fun string(): String = String(bytes(), Charsets.UTF_8)

    fun <T> vector(item: () -> T): List<T> {
        val id = int()
        if (id == Tl.GZIP_PACKED) {
            return TlReader(Tl.gunzip(bytes())).vector(item)
        }
        if (id != Tl.VECTOR) throw TlException("Expected vector, got 0x%08x".format(id))
        return bareVector(item)
    }

    fun <T> bareVector(item: () -> T): List<T> {
        val n = int()
        if (n < 0 || n > remaining) throw TlException("Bad vector size $n")
        val out = ArrayList<T>(n)
        repeat(n) { out.add(item()) }
        return out
    }

    fun obj(): TlObject {
        val id = int()
        if (id == Tl.GZIP_PACKED) {
            return TlReader(Tl.gunzip(bytes())).obj()
        }
        val read = Tl.reader(id) ?: throw TlException("Unknown constructor 0x%08x".format(id))
        return read(this)
    }

    fun peekInt(): Int {
        val v = int()
        pos -= 4
        return v
    }

    fun skip(n: Int) {
        need(n)
        pos += n
    }
}

/** `invokeWithLayer`, `initConnection` and friends: generic wrappers around another query. */
class InvokeWithLayer<R>(val layer: Int, val query: TlFunction<R>) : TlFunction<R> {
    override val constructorId: Int get() = 0xda9b0d0d.toInt()
    override fun serializeBody(w: TlWriter) {
        w.int(layer)
        query.serialize(w)
    }

    override fun parseResult(r: TlReader): R = query.parseResult(r)
}

class InitConnection<R>(
    val apiId: Int,
    val deviceModel: String,
    val systemVersion: String,
    val appVersion: String,
    val systemLangCode: String,
    val langPack: String,
    val langCode: String,
    val query: TlFunction<R>,
) : TlFunction<R> {
    override val constructorId: Int get() = 0xc1cd5ea9.toInt()
    override fun serializeBody(w: TlWriter) {
        w.int(0)
        w.int(apiId)
        w.string(deviceModel)
        w.string(systemVersion)
        w.string(appVersion)
        w.string(systemLangCode)
        w.string(langPack)
        w.string(langCode)
        query.serialize(w)
    }

    override fun parseResult(r: TlReader): R = query.parseResult(r)
}

class InvokeWithTakeout<R>(val takeoutId: Long, val query: TlFunction<R>) : TlFunction<R> {
    override val constructorId: Int get() = 0xaca9fd2e.toInt()
    override fun serializeBody(w: TlWriter) {
        w.long(takeoutId)
        query.serialize(w)
    }

    override fun parseResult(r: TlReader): R = query.parseResult(r)
}

class InvokeWithoutUpdates<R>(val query: TlFunction<R>) : TlFunction<R> {
    override val constructorId: Int get() = 0xbf9459b7.toInt()
    override fun serializeBody(w: TlWriter) = query.serialize(w)
    override fun parseResult(r: TlReader): R = query.parseResult(r)
}

/** Raw bytes already serialized elsewhere (used for re-sending). */
class TlRaw(private val bytes: ByteArray) : TlObject {
    override val constructorId: Int get() = TlReader(bytes).int()
    override fun serializeBody(w: TlWriter) = w.raw(bytes.copyOfRange(4, bytes.size))
    override fun serialize(w: TlWriter) = w.raw(bytes)
}
