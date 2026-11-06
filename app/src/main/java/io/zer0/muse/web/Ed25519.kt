package io.zer0.muse.web

import java.math.BigInteger
import java.security.MessageDigest

/**
 * v2.0: Ed25519 núcleo(Implementación pura de Kotlin/BigInteger) — Se utiliza para la verificación de la firma del webhook.
 *
 * Alcance de implementación(RFC 8032 conjunto mínimo):
 *  - [seedToPublicKey]: 32 Semilla de bytes para derivar una clave pública de 32 bytes;
 *  - [sign]: Firma determinista(respuesta al desafío);
 *  - [verify]: Verificación(Empuje de eventos a prueba de manipulaciones).
 *
 * No se introducen dependencias criptográficas externas.,Asegúrese de que haya versiones inferiores de Android disponibles;
 * La corrección está cubierta por la prueba única de vector de prueba oficial(ver Ed25519Test).
 */
internal object Ed25519 {

    // ── constante ──────────────────────────────────────────────────────────
    /** p = 2^255 - 19. */
    private val P: BigInteger =
        BigInteger.TWO.pow(255) - BigInteger.valueOf(19)

    /** L = 2^252 + 27742317777372353535851937790883648493(Nivel de grupo). */
    private val L: BigInteger =
        BigInteger.TWO.pow(252) +
            BigInteger("27742317777372353535851937790883648493")

    /** d = -121665/121666 mod p. */
    private val D: BigInteger =
        BigInteger.valueOf(-121665).mod(P)
            .multiply(BigInteger.valueOf(121666).modInverse(P))
            .mod(P)

    /** 2 * d(Haga clic para agregar). */
    private val D2: BigInteger = D.multiply(BigInteger.TWO).mod(P)

    /** sqrt(-1) mod p(Factores de corrección al resolver para x). */
    private val SQRT_M1: BigInteger =
        BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)

    /** punto base y = 4/5 mod p. */
    private val BASE_Y: BigInteger =
        BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)

    /** punto base x(Obtenga una solución uniforme). */
    private val BASE_X: BigInteger = recoverX(BASE_Y, signBit = 0)
        ?: error("Ed25519 La recuperación del punto base falló")

    private val BASE = Point(BASE_X, BASE_Y, BigInteger.ONE, BASE_X.multiply(BASE_Y).mod(P))
    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    private val SHA512 = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-512") }

    // ── publico API ──────────────────────────────────────────────────────

    /** 32 semilla de bytes → 32 Clave pública de bytes. */
    fun seedToPublicKey(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "seed Debe tener 32 bytes" }
        val h = sha512(seed)
        val a = clamp(h.copyOfRange(0, 32))
        val aScalar = leToInt(a)
        return encodePoint(scalarMult(BASE, aScalar))
    }

    /**
     * Firma determinista(RFC 8032).
     *
     * @return 64 Firma de bytes(R || s)
     */
    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        require(seed.size == 32) { "seed Debe tener 32 bytes" }
        val h = sha512(seed)
        val aScalar = leToInt(clamp(h.copyOfRange(0, 32)))
        val prefix = h.copyOfRange(32, 64)
        val publicKey = encodePoint(scalarMult(BASE, aScalar))

        val rScalar = leToInt(sha512(prefix, message)).mod(L)
        val rEncoded = encodePoint(scalarMult(BASE, rScalar))
        val k = leToInt(sha512(rEncoded, publicKey, message)).mod(L)
        val s = rScalar.add(k.multiply(aScalar)).mod(L)
        return rEncoded + intToLe(s)
    }

    /** Verificación:Clave pública 32 bytes,Firma 64 bytes,message Cualquier longitud. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decodePoint(publicKey) ?: return false
        val r = decodePoint(signature.copyOfRange(0, 32)) ?: return false
        val s = leToInt(signature.copyOfRange(32, 64))
        if (s >= L) return false
        val k = leToInt(sha512(signature.copyOfRange(0, 32), publicKey, message)).mod(L)
        // verificar:s·B == R + k·A
        val left = encodePoint(scalarMult(BASE, s))
        val right = encodePoint(pointAdd(r, scalarMult(a, k)))
        return left.contentEquals(right)
    }

    /** Contraseñas completas de cualquier longitud/Truncado a semilla de 32 bytes(Repita el empalme para ≥32 Después de tomar el frente 32). */
    fun seedFromSecret(secret: String): ByteArray {
        var s = secret
        while (s.toByteArray(Charsets.UTF_8).size < 32) {
            s += secret
        }
        return s.toByteArray(Charsets.UTF_8).copyOf(32)
    }

    // ── Operación de curva(Coordenadas extendidas) ────────────────────────────────────────────

    private data class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private fun pointAdd(p: Point, q: Point): Point {
        val a = (p.y.subtract(p.x)).multiply(q.y.subtract(q.x)).mod(P)
        val b = (p.y.add(p.x)).multiply(q.y.add(q.x)).mod(P)
        val c = p.t.multiply(q.t).multiply(D2).mod(P)
        val d = p.z.multiply(q.z).multiply(BigInteger.TWO).mod(P)
        val e = b.subtract(a).mod(P)
        val f = d.subtract(c).mod(P)
        val g = d.add(c).mod(P)
        val h = b.add(a).mod(P)
        return Point(
            e.multiply(f).mod(P),
            g.multiply(h).mod(P),
            f.multiply(g).mod(P),
            e.multiply(h).mod(P),
        )
    }

    private fun scalarMult(point: Point, scalar: BigInteger): Point {
        var result = IDENTITY
        var addend = point
        var k = scalar
        while (k > BigInteger.ZERO) {
            if (k.testBit(0)) result = pointAdd(result, addend)
            addend = pointAdd(addend, addend)
            k = k.shiftRight(1)
        }
        return result
    }

    /** restaurado por y x;signBit especifica la paridad de x(0 par / 1 impar);Devuelve nulo si no hay solución. */
    private fun recoverX(y: BigInteger, signBit: Int): BigInteger? {
        val y2 = y.multiply(y).mod(P)
        val numerator = y2.subtract(BigInteger.ONE).mod(P)
        val denominator = D.multiply(y2).add(BigInteger.ONE).mod(P)
        val xx = numerator.multiply(denominator.modInverse(P)).mod(P)
        var x = xx.modPow(
            P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)),
            P,
        )
        if (x.multiply(x).subtract(xx).mod(P) != BigInteger.ZERO) {
            x = x.multiply(SQRT_M1).mod(P)
        }
        if (x.multiply(x).subtract(xx).mod(P) != BigInteger.ZERO) return null
        if ((if (x.testBit(0)) 1 else 0) != signBit) {
            x = P.subtract(x)
        }
        return x
    }

    /** punto → 32 bytes(Little endian y + almacenes de bits superiores x paridad). */
    private fun encodePoint(point: Point): ByteArray {
        val zInv = point.z.modInverse(P)
        val x = point.x.multiply(zInv).mod(P)
        val y = point.y.multiply(zInv).mod(P)
        val out = ByteArray(32)
        var t = y
        for (i in 0 until 32) {
            out[i] = t.and(BigInteger.valueOf(0xFF)).toByte()
            t = t.shiftRight(8)
        }
        if (x.testBit(0)) {
            out[31] = (out[31].toInt() or 0x80).toByte()
        }
        return out
    }

    /** 32 bytes → punto;La codificación no válida devuelve nulo. */
    private fun decodePoint(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        val yBytes = bytes.copyOf()
        val signBit = (yBytes[31].toInt() ushr 7) and 1
        yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte()
        val y = leToInt(yBytes)
        if (y >= P) return null
        val x = recoverX(y, signBit) ?: return null
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    // ── Herramientas ──────────────────────────────────────────────────────────

    /** RFC 8032 clamp(Borre los 3 bits inferiores, borre el bit más alto y establezca el segundo bit más alto.). */
    private fun clamp(h: ByteArray): ByteArray {
        h[0] = (h[0].toInt() and 248).toByte()
        h[31] = (h[31].toInt() and 63).toByte()
        h[31] = (h[31].toInt() or 64).toByte()
        return h
    }

    private fun sha512(vararg parts: ByteArray): ByteArray {
        val digest = SHA512.get()!!
        digest.reset()
        parts.forEach { digest.update(it) }
        return digest.digest()
    }

    /** pequeño byte endian → Entero no negativo. */
    private fun leToInt(bytes: ByteArray): BigInteger {
        val be = bytes.reversedArray()
        return BigInteger(1, be)
    }

    /** entero no negativo → 32 Bytes little endian. */
    private fun intToLe(value: BigInteger): ByteArray {
        val out = ByteArray(32)
        var t = value
        for (i in 0 until 32) {
            out[i] = t.and(BigInteger.valueOf(0xFF)).toByte()
            t = t.shiftRight(8)
        }
        return out
    }
}
