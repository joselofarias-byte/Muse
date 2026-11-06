package io.zer0.muse.rag

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.PriorityQueue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

internal class HnswEmbeddingFingerprintMismatchException :
    IllegalArgumentException("HNSW embedding fingerprint does not match the active provider")

/**
 * v1.55: Interfaz unificada de indexación de vectores.
 *
 * para la abstracción"Cómo se almacenan y recuperan los vectores",Admite el cambio posterior de diferentes algoritmos ANN
 * (Implementación actual:[HnswVectorIndex];El recorrido de fuerza bruta reutiliza lo existente [VectorSearchService]).
 */
interface VectorIndex {
    /** Agregar vector(Si ya existe la misma identificación, se sobrescribirá.). */
    fun add(id: String, vector: FloatArray)

    /** Recuperar k vecinos más cercanos(Regresar en orden descendente de similitud). */
    fun search(query: FloatArray, k: Int): List<SearchResult>

    /** eliminar especificado id(Ignorar si no existe). */
    fun remove(id: String)

    /** El número de vectores en el índice actual. */
    val size: Int

    /** Borre el índice actual. */
    fun clear()

    /** persistir en el archivo(Sobrescribir contenido existente). */
    fun save(file: File, fingerprint: String? = null)

    /** Cargar desde archivo(Reemplazar el contenido del índice actual). */
    fun load(file: File, expectedFingerprint: String? = null)
}

/**
 * v1.55: Resultados de búsqueda de vectores.
 *
 * @property id chunk logotipo(corresponder [io.zer0.muse.data.knowledge.KnowledgeChunkEntity.id])
 * @property score similitud coseno(alcance [-1,1],Cuanto más grande, más parecido;normalmente [0,1])
 * @property distance distancia coseno(alcance [0,2],Cuanto más pequeños, más parecidos;= 1 - [score])
 */
data class SearchResult(
    val id: String,
    val score: Float,
    val distance: Float,
)

/**
 * v1.55: HNSW(Hierarchical Navigable Small World)Índice de vecino más cercano aproximado.
 *
 * Implementación pura de Kotlin,No se introducen bibliotecas de terceros.(Evite el exceso de dependencia).
 * Descripción del algoritmo:Malkov & Yashunin 2016 "Efficient and robust approximate nearest
 * neighbor search using Hierarchical Navigable Small World graphs".
 *
 * parámetros:
 *  - [M]:Número máximo de conexiones por capa(Límite de nivel 0 = 2*M,otras capas = M)
 *  - [efConstruction]:Tamaño de selección en el momento de la construcción(Cuanto mayor sea el valor, mayor será el retiro.,Cuanto más lenta sea la construcción)
 *  - [efSearch]:Tamaño de selección al realizar consultas(Cuanto mayor sea el valor, mayor será el retiro.,La consulta es más lenta.)
 *  - [maxLayer]:Nivel máximo actual(Mantenimiento en tiempo de ejecución,inicial -1)
 *
 * medida de distancia:distancia coseno = 1 - similitud coseno(alcance [0,2],Cuanto más pequeños, más parecidos).
 * No requiere que los vectores estén normalizados L2 de antemano,Internamente, el punto/norma se calcula de forma independiente para cada par de vectores.
 *
 * Seguridad del hilo:[ReentrantReadWriteLock] — Leer operación(search/size/save)paralelo,operación de escritura(add/remove/load)exclusivo.
 *
 * persistencia:formato binario(magic header + version + Parámetro + recuento de nodos + cada nodo layer/connections/vectors).
 * Eliminar política:eliminación suave(marca deleted=true),Eliminación de compresión al guardar;Las ranuras de índice no se reutilizan para evitar una reasignación extensa de vecinos.
 *
 * @param M Número máximo de conexiones por capa,Predeterminado 16
 * @param efConstruction Tamaño de selección en el momento de la construcción,Predeterminado 200
 * @param efSearch Tamaño de selección al realizar consultas,Predeterminado 50
 * @param seed semilla aleatoria(Proceso de construcción reproducible)
 */
class HnswVectorIndex(
    private val M: Int = DEFAULT_M,
    private val efConstruction: Int = DEFAULT_EF_CONSTRUCTION,
    private val efSearch: Int = DEFAULT_EF_SEARCH,
    private val seed: Long = DEFAULT_SEED,
) : VectorIndex {

    /** nodo interno:Vector + nivel + vecinos en cada nivel(Referenciado con índice interno.). */
    private class Node(
        val id: String,
        val vector: FloatArray,
        val level: Int,
        val connections: Array<MutableList<Int>>,
        @Volatile var deleted: Boolean = false,
    )

    private data class LoadedState(
        val nodes: List<Node>,
        val idToIndex: Map<String, Int>,
        val maxLayer: Int,
        val entryPoint: Int,
    )

    private val nodes = mutableListOf<Node>() // interno index → Node
    private val idToIndex = HashMap<String, Int>() // externo id → interno index

    @Volatile private var entryPoint: Int = -1 // punto de entrada index

    @Volatile private var maxLayer: Int = -1 // Nivel máximo actual
    private val rng = Random(seed)
    private val lock = ReentrantReadWriteLock()
    private val saveLock = Any()

    /** mL = 1 / ln(M),Se utiliza para generar jerarquías de nodos según una distribución exponencial.(Fórmula de tesis). */
    private val mL: Double = 1.0 / ln(M.toDouble())

    override val size: Int
        get() = lock.read { idToIndex.size }

    override fun clear() = lock.write {
        nodes.clear()
        idToIndex.clear()
        entryPoint = -1
        maxLayer = -1
    }

    override fun add(id: String, vector: FloatArray) = lock.write {
        validateVector(id, vector)
        idToIndex.remove(id)?.let { softDeleteInternal(it) }

        val level = randomLevel()
        val node = Node(id, vector.copyOf(), level, Array(level + 1) { mutableListOf() })
        val newIndex = nodes.size
        nodes.add(node)
        idToIndex[id] = newIndex

        if (entryPoint < 0 || maxLayer < 0) {
            entryPoint = newIndex
            maxLayer = level
            return@write
        }
        connectNode(node, newIndex, vector)
        if (level > maxLayer) {
            entryPoint = newIndex
            maxLayer = level
        }
    }

    private fun validateVector(id: String, vector: FloatArray) {
        require(vector.isNotEmpty()) { "HNSW vectors must not be empty" }
        val expectedDimension = idToIndex.entries
            .firstOrNull { it.key != id }
            ?.value
            ?.let { nodes[it].vector.size }
        require(expectedDimension == null || vector.size == expectedDimension) {
            "HNSW vector dimension ${vector.size} does not match active index dimension $expectedDimension"
        }
    }

    private fun connectNode(node: Node, newIndex: Int, vector: FloatArray) {
        var cur = entryPoint
        for (layer in maxLayer downTo node.level + 1) {
            cur = searchLayer(vector, listOf(cur), layer, ef = 1)
                .firstOrNull()?.second ?: cur
        }
        for (layer in minOf(node.level, maxLayer) downTo 0) {
            val candidates = searchLayer(vector, listOf(cur), layer, efConstruction)
                .filter { it.second != newIndex }
            val selected = selectNeighborsHeuristic(candidates, M)
            for ((_, neighborIdx) in selected) {
                if (neighborIdx == newIndex) continue
                node.connections[layer].add(neighborIdx)
                val neighbor = nodes[neighborIdx]
                neighbor.connections[layer].add(newIndex)
                pruneNeighborConnections(neighbor, layer)
            }
            cur = candidates.firstOrNull()?.second ?: cur
        }
    }

    private fun pruneNeighborConnections(neighbor: Node, layer: Int) {
        val mMax = if (layer == 0) M * 2 else M
        if (neighbor.connections[layer].size <= mMax) return
        val candidates = neighbor.connections[layer]
            .map { idx -> distance(neighbor.vector, nodes[idx].vector) to idx }
        val pruned = selectNeighborsHeuristic(candidates, mMax)
        neighbor.connections[layer].clear()
        neighbor.connections[layer].addAll(pruned.map { it.second })
    }

    override fun search(query: FloatArray, k: Int): List<SearchResult> = lock.read {
        if (entryPoint < 0) return@read emptyList()
        if (nodes.isEmpty()) return@read emptyList()
        if (k <= 0) return@read emptyList()
        if (query.isEmpty()) return@read emptyList()
        val expectedDimension = idToIndex.values.firstOrNull()?.let { nodes[it].vector.size }
            ?: return@read emptyList()
        if (query.size != expectedDimension) return@read emptyList()
        // Arriba a 1: Codicioso 1-NN
        var cur = entryPoint
        for (layer in maxLayer downTo 1) {
            cur = searchLayer(query, listOf(cur), layer, ef = 1)
                .firstOrNull()?.second ?: cur
        }
        // Capa 0: búsqueda ef
        val ef = maxOf(efSearch, k)
        val candidates = searchLayer(query, listOf(cur), 0, ef)
        return@read candidates
            .take(k)
            .map { (dist, idx) ->
                val node = nodes[idx]
                SearchResult(id = node.id, score = 1f - dist, distance = dist)
            }
    }

    override fun remove(id: String) = lock.write {
        val idx = idToIndex.remove(id) ?: return@write
        softDeleteInternal(idx)
    }

    /**
     * Llamada dentro del bloqueo de escritura:Eliminación temporal del nodo en el índice especificado.
     *
     * darse cuenta:marca deleted=true + Quitarte de todas las conexiones vecinas;
     * Si se elimina el punto de entrada,Seleccione el nivel más alto entre los nodos restantes como nueva entrada.
     * Sin eliminación física de nodos.(Evite la desalineación del índice que lleva a una reasignación de vecinos a gran escala).
     */
    private fun softDeleteInternal(removedIdx: Int) {
        if (removedIdx !in nodes.indices) return
        val removed = nodes[removedIdx]
        removed.deleted = true
        // Quitarte de todas las conexiones vecinas
        for (layer in 0..removed.level) {
            for (neighborIdx in removed.connections[layer].toList()) {
                nodes.getOrNull(neighborIdx)?.connections?.getOrNull(layer)?.remove(removedIdx)
            }
        }
        // Punto de entrada eliminado → Encuentre el nivel más alto entre los nodos restantes no eliminados
        if (entryPoint == removedIdx) {
            var newEntry = -1
            var newMaxLayer = -1
            for (i in nodes.indices) {
                val n = nodes[i]
                if (n.deleted) continue
                if (n.level > newMaxLayer) {
                    newMaxLayer = n.level
                    newEntry = i
                }
            }
            entryPoint = newEntry
            maxLayer = newMaxLayer
        }
    }

    /**
     * Realizar una búsqueda en la capa especificada(papel Algorithm 2).
     *
     * @param query vector de consulta
     * @param entryPoints Lista de índice de puntos de entrada
     * @param layer capa objetivo
     * @param ef Tamaño del conjunto de candidatos dinámicos
     * @return lista de candidatos(distance, index),Ordenar por distancia ascendente
     */
    private fun searchLayer(query: FloatArray, entryPoints: List<Int>, layer: Int, ef: Int): List<Pair<Float, Int>> {
        if (entryPoints.isEmpty()) return emptyList()
        val visited = HashSet<Int>()
        // candidates: min-heap by distance (smaller = closer = dequeued first)
        val candidates = PriorityQueue<Pair<Float, Int>>(compareBy { it.first })
        // max-heap by distance (larger = farther = polled first when pruning)
        val best = PriorityQueue<Pair<Float, Int>>(compareByDescending { it.first })

        for (ep in entryPoints) {
            if (ep !in nodes.indices) continue
            val node = nodes[ep]
            if (node.deleted) continue
            if (ep in visited) continue
            val d = distance(query, node.vector)
            candidates.offer(d to ep)
            best.offer(d to ep)
            visited.add(ep)
        }

        while (candidates.isNotEmpty()) {
            val (cDist, cIdx) = candidates.poll() ?: break
            val fDist = best.peek()?.first ?: Float.MAX_VALUE
            if (cDist > fDist) break
            val cNode = nodes[cIdx]
            val conns = cNode.connections.getOrNull(layer) ?: continue
            for (e in conns) {
                if (e in visited) continue
                visited.add(e)
                if (e !in nodes.indices) continue
                val eNode = nodes[e]
                if (eNode.deleted) continue
                val eDist = distance(query, eNode.vector)
                val fDist2 = best.peek()?.first ?: Float.MAX_VALUE
                if (eDist < fDist2 || best.size < ef) {
                    candidates.offer(eDist to e)
                    best.offer(eDist to e)
                    if (best.size > ef) best.poll()
                }
            }
        }

        return best.toList().sortedBy { it.first } // orden ascendente:más cercano primero
    }

    /**
     * heurística de selección de vecinos(papel Algorithm 4).
     *
     * reglas:Seleccione los resultados de los candidatos en orden ascendente de distancia;Si la distancia entre el candidato e y el seleccionado r
     * más cerca que e para consultar,luego salta e(evitar reuniones).
     * Si el filtrado heurístico es insuficiente m,llenar(Permitir la suscripción no heurística).
     */
    private fun selectNeighborsHeuristic(candidates: List<Pair<Float, Int>>, m: Int): List<Pair<Float, Int>> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates.sortedBy { it.first } // orden ascendente:más cercano a la consulta primero
        val result = mutableListOf<Pair<Float, Int>>()
        for ((eDist, eIdx) in sorted) {
            if (result.size >= m) break
            // heurístico:e La distancia a consultar debe ser más cercana que la distancia de e a cualquier r seleccionado
            var keep = true
            for ((_, rIdx) in result) {
                val dER = distance(nodes[eIdx].vector, nodes[rIdx].vector)
                if (dER < eDist) {
                    keep = false
                    break
                }
            }
            if (keep) result.add(eDist to eIdx)
        }
        // Insuficiente después del filtrado heurístico m → llenar
        if (result.size < m) {
            val existing = result.map { it.second }.toSet()
            for ((eDist, eIdx) in sorted) {
                if (result.size >= m) break
                if (eIdx in existing) continue
                result.add(eDist to eIdx)
            }
        }
        return result
    }

    /** Generar jerarquía de nodos según distribución exponencial.:level = floor(-ln(uniform(0,1)) * mL). */
    private fun randomLevel(): Int {
        val r = rng.nextDouble()
        // prevenir r=0 Causar que el registro diverja
        val safeR = if (r <= 0.0) 1e-10 else r
        return Math.floor(-Math.log(safeR) * mL).toInt()
    }

    /** distancia coseno = 1 - similitud coseno(alcance [0,2]);Las dimensiones deben ser consistentes.,Error de cálculo sin prefijo. */
    private fun distance(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "HNSW distance requires equal vector dimensions" }
        if (a.isEmpty()) return 1f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            val av = a[i]
            val bv = b[i]
            dot += av * bv
            na += av * av
            nb += bv * bv
        }
        val denom = sqrt(na) * sqrt(nb)
        if (denom == 0f) return 1f
        return 1f - dot / denom
    }

    override fun save(file: File, fingerprint: String?) = synchronized(saveLock) {
        val parent = checkNotNull(file.absoluteFile.parentFile)
        check(parent.exists() || parent.mkdirs()) { "No se puede crear el directorio de índice HNSW: ${parent.absolutePath}" }
        // El bloqueo de lectura durante la serialización permite el paralelismo de recuperación;saveLock Se garantiza que los archivos temporales y el eventual reemplazo serán serializados.
        val tmp = File(parent, "${file.name}.${java.util.UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(tmp).use { fileOutput ->
                DataOutputStream(fileOutput).use { out ->
                    lock.read { writePayload(out, fingerprint) }
                    out.flush()
                    fileOutput.fd.sync()
                }
            }
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            Unit
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

/**
     * HEADER + NODES carga útil serializada(suministro [save] Escribir en un archivo temporal).
     *
     * Primero recopile los nodos supervivientes y cree los antiguos. index → Mapeo de índice compacto,Luego genere el id/nivel/vector nodo por nodo/Conexiones vecinales a todos los niveles.
     */
    private fun writePayload(out: DataOutputStream, fingerprint: String?) {
        // HEADER
        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        out.writeInt(VERSION)
        out.writeInt(M)
        out.writeInt(efConstruction)
        out.writeInt(efSearch)
        val fingerprintBytes = fingerprint.orEmpty().toByteArray(Charsets.UTF_8)
        require(fingerprintBytes.size <= MAX_FINGERPRINT_BYTES) { "HNSW fingerprint is too long" }
        out.writeInt(fingerprintBytes.size)
        out.write(fingerprintBytes)
        out.writeInt(maxLayer)

        // Recoge nodos supervivientes + crea viejos index → nuevo(Compacto)index mapeo
        val alive = nodes.mapIndexedNotNull { idx, node ->
            if (!node.deleted) idx to node else null
        }
        val oldToNew = HashMap<Int, Int>()
        alive.forEachIndexed { newIdx, (oldIdx, _) -> oldToNew[oldIdx] = newIdx }

        out.writeInt(oldToNew[entryPoint] ?: -1)
        out.writeInt(alive.size)

        // NODES
        for ((_, node) in alive) {
            val idBytes = node.id.toByteArray(Charsets.UTF_8)
            out.writeInt(idBytes.size)
            out.write(idBytes)
            out.writeInt(node.level)
            out.writeInt(node.vector.size)
            for (v in node.vector) out.writeFloat(v)
            out.writeInt(node.level + 1)
            for (layer in 0..node.level) {
                // Filtrar conexiones a nodos eliminados(El índice antiguo no está en oldToNew.)
                val conns = node.connections[layer].mapNotNull { oldToNew[it] }
                out.writeInt(conns.size)
                for (c in conns) out.writeInt(c)
            }
        }
    }

    override fun load(file: File, expectedFingerprint: String?) = lock.write {
        if (!file.exists()) return@write
        DataInputStream(FileInputStream(file)).use { inp ->
            val loadedState = requireNotNull(readLoadedState(inp, expectedFingerprint)) {
                "HNSW index header is unsupported or invalid"
            }
            nodes.clear()
            nodes.addAll(loadedState.nodes)
            idToIndex.clear()
            idToIndex.putAll(loadedState.idToIndex)
            maxLayer = loadedState.maxLayer
            entryPoint = loadedState.entryPoint
        }
    }

    private fun readLoadedState(inp: DataInputStream, expectedFingerprint: String?): LoadedState? {
        val magicBuf = ByteArray(MAGIC.length)
        inp.readFully(magicBuf)
        val magicValid = String(magicBuf, Charsets.US_ASCII) == MAGIC
        val version = if (magicValid) inp.readInt() else VERSION
        if (!magicValid || version != VERSION) return null
        inp.readInt() // M
        inp.readInt() // efConstruction
        inp.readInt() // efSearch
        val fingerprintLength = inp.readInt()
        require(fingerprintLength in 0..MAX_FINGERPRINT_BYTES) { "HNSW fingerprint length is invalid" }
        val fingerprintBytes = ByteArray(fingerprintLength)
        inp.readFully(fingerprintBytes)
        val loadedFingerprint = String(fingerprintBytes, Charsets.UTF_8)
        if (expectedFingerprint != null && loadedFingerprint != expectedFingerprint) {
            throw HnswEmbeddingFingerprintMismatchException()
        }
        val maxLayer = inp.readInt()
        val entryPoint = inp.readInt()
        val nodeCount = inp.readInt()
        require(nodeCount >= 0) { "HNSW node count must not be negative" }
        val (nodes, idToIndex) = readNodes(inp, nodeCount)
        validateLoadedState(nodes, maxLayer, entryPoint)
        return LoadedState(nodes, idToIndex, maxLayer, entryPoint)
    }

    private fun readNodes(inp: DataInputStream, nodeCount: Int): Pair<List<Node>, Map<String, Int>> {
        val loadedNodes = ArrayList<Node>(nodeCount)
        val loadedIdToIndex = HashMap<String, Int>(nodeCount)
        var expectedDimension: Int? = null
        for (i in 0 until nodeCount) {
            val idLen = inp.readInt()
            require(idLen >= 0) { "HNSW node id length must not be negative" }
            val idBytes = ByteArray(idLen)
            inp.readFully(idBytes)
            val id = String(idBytes, Charsets.UTF_8)
            val level = inp.readInt()
            require(level >= 0) { "HNSW node level must not be negative" }
            val dim = inp.readInt()
            require(dim > 0) { "HNSW vector dimension must be positive" }
            require(expectedDimension == null || dim == expectedDimension) {
                "HNSW index contains mixed vector dimensions"
            }
            expectedDimension = dim
            val vector = FloatArray(dim) { inp.readFloat() }
            val layerCount = inp.readInt()
            require(layerCount == level + 1) { "HNSW node layer count does not match its level" }
            val connections = readConnections(inp, nodeCount, layerCount)
            require(loadedIdToIndex.put(id, i) == null) { "HNSW index contains duplicate node ids" }
            loadedNodes.add(Node(id, vector, level, connections))
        }
        return loadedNodes to loadedIdToIndex
    }

    private fun readConnections(inp: DataInputStream, nodeCount: Int, layerCount: Int): Array<MutableList<Int>> = Array(layerCount) {
        val connectionCount = inp.readInt()
        require(connectionCount in 0..nodeCount) { "HNSW connection count is invalid" }
        MutableList(connectionCount) {
            val connection = inp.readInt()
            require(connection in 0 until nodeCount) { "HNSW connection index is invalid" }
            connection
        }
    }

    private fun validateLoadedState(nodes: List<Node>, maxLayer: Int, entryPoint: Int) {
        nodes.forEachIndexed { nodeIndex, node ->
            node.connections.forEachIndexed { layer, connections ->
                connections.forEach { connection ->
                    require(connection != nodeIndex) { "HNSW node cannot connect to itself" }
                    require(nodes[connection].level >= layer) {
                        "HNSW connection targets a node missing the referenced layer"
                    }
                }
            }
        }
        if (nodes.isEmpty()) {
            require(entryPoint == -1 && maxLayer == -1) { "Empty HNSW index has invalid entry point" }
            return
        }
        require(entryPoint in nodes.indices) { "HNSW entry point is invalid" }
        require(maxLayer == nodes.maxOf { it.level }) {
            "HNSW maximum layer does not match loaded nodes"
        }
        require(nodes[entryPoint].level == maxLayer) {
            "HNSW entry point does not match the maximum layer"
        }
    }

    companion object {
        /** Predeterminado M: Número máximo de conexiones por capa.M=16 La velocidad de recuperación y construcción están bien equilibradas en la mayoría de los escenarios. */
        const val DEFAULT_M = 16

        /** EfConstruction predeterminado: tamaño de selección al construir.200 Es el valor recomendado del papel. */
        const val DEFAULT_EF_CONSTRUCTION = 200

        /** EfSearch predeterminado: tamaño de selección al realizar consultas.50 Teniendo en cuenta las retiradas(>95%)y velocidad. */
        const val DEFAULT_EF_SEARCH = 50

        /** Semilla aleatoria predeterminada(Proceso de construcción reproducible). */
        const val DEFAULT_SEED = 42L

        private const val MAX_FINGERPRINT_BYTES = 4096

        /** archivo de persistencia magic header(6 bytes ASCII). */
        private const val MAGIC = "HNSW01"

        /** Versión de archivo persistente(v2 Agregar proveedor de incrustación/modelo/huella digital de dimensión). */
        private const val VERSION = 2
    }
}
