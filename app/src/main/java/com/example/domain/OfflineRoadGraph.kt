package com.example.domain

import com.example.data.model.LatLonPoint
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.PriorityQueue
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

data class OfflineRouteResult(
    val points: List<LatLonPoint>,
    val distanceMeters: Double,
    val estimatedSeconds: Long
)

/**
 * Compact, CPU-friendly bidirectional walking graph stored in Compressed Sparse Row (CSR) layout.
 * Serializes quickly to/from disk for zero-internet A* pedestrian routing.
 */
class OfflineRoadGraph(
    val lats: DoubleArray,
    val lons: DoubleArray,
    val headEdge: IntArray,
    val edgeCountForNode: IntArray,
    val edgeTargets: IntArray,
    val edgeWeightsMeters: FloatArray
) {
    val nodeCount: Int get() = lats.size
    val edgeCount: Int get() = edgeTargets.size

    fun writeTo(outputStream: OutputStream) {
        val out = DataOutputStream(BufferedOutputStream(outputStream, 16 * 1024))
        out.writeInt(MAGIC)
        out.writeInt(FORMAT_VERSION)
        out.writeInt(nodeCount)
        out.writeInt(edgeCount)
        for (i in 0 until nodeCount) {
            out.writeDouble(lats[i])
            out.writeDouble(lons[i])
            out.writeInt(headEdge[i])
            out.writeInt(edgeCountForNode[i])
        }
        for (e in 0 until edgeCount) {
            out.writeInt(edgeTargets[e])
            out.writeFloat(edgeWeightsMeters[e])
        }
        out.flush()
    }

    /**
     * Runs A* shortest walking path search completely on the CPU between arbitrary (startLat, startLon)
     * and (endLat, endLon) by snapping onto the closest walkable footpath/street segment.
     */
    fun findWalkingRoute(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        maxSnapMeters: Double = 2500.0
    ): OfflineRouteResult? {
        if (nodeCount == 0 || edgeCount == 0) return null

        val directDist = GeoGrid.haversineMeters(startLat, startLon, endLat, endLon)
        if (directDist <= 3.0) {
            return OfflineRouteResult(
                points = listOf(
                    LatLonPoint(startLat, startLon),
                    LatLonPoint(endLat, endLon)
                ),
                distanceMeters = directDist,
                estimatedSeconds = (directDist / WALKING_SPEED_MPS).toLong().coerceAtLeast(1L)
            )
        }

        val startSnap = findNearestEdgeSnap(startLat, startLon, maxSnapMeters) ?: return null
        val endSnap = findNearestEdgeSnap(endLat, endLon, maxSnapMeters) ?: return null

        // Shortcut if both endpoints snap onto the same street/footpath segment
        if ((startSnap.nodeA == endSnap.nodeA && startSnap.nodeB == endSnap.nodeB) ||
            (startSnap.nodeA == endSnap.nodeB && startSnap.nodeB == endSnap.nodeA)
        ) {
            val pts = deduplicatePoints(
                listOf(
                    LatLonPoint(startLat, startLon),
                    LatLonPoint(startSnap.snapLat, startSnap.snapLon),
                    LatLonPoint(endSnap.snapLat, endSnap.snapLon),
                    LatLonPoint(endLat, endLon)
                )
            )
            val dist = polylineDistanceMeters(pts)
            return OfflineRouteResult(
                points = pts,
                distanceMeters = dist,
                estimatedSeconds = (dist / WALKING_SPEED_MPS).toLong().coerceAtLeast(1L)
            )
        }

        val gScore = DoubleArray(nodeCount) { Double.POSITIVE_INFINITY }
        val parent = IntArray(nodeCount) { -1 }
        val closed = BooleanArray(nodeCount)
        val queue = PriorityQueue<AStarEntry>(64)

        // Seed A* from both endpoints of the snapped start edge
        val distStartA = GeoGrid.haversineMeters(startSnap.snapLat, startSnap.snapLon, lats[startSnap.nodeA], lons[startSnap.nodeA])
        val distStartB = GeoGrid.haversineMeters(startSnap.snapLat, startSnap.snapLon, lats[startSnap.nodeB], lons[startSnap.nodeB])

        gScore[startSnap.nodeA] = distStartA
        queue.add(
            AStarEntry(
                node = startSnap.nodeA,
                fScore = distStartA + GeoGrid.haversineMeters(lats[startSnap.nodeA], lons[startSnap.nodeA], endSnap.snapLat, endSnap.snapLon)
            )
        )
        if (startSnap.nodeB != startSnap.nodeA && distStartB < gScore[startSnap.nodeB]) {
            gScore[startSnap.nodeB] = distStartB
            queue.add(
                AStarEntry(
                    node = startSnap.nodeB,
                    fScore = distStartB + GeoGrid.haversineMeters(lats[startSnap.nodeB], lons[startSnap.nodeB], endSnap.snapLat, endSnap.snapLon)
                )
            )
        }

        val endEdgeDistA = GeoGrid.haversineMeters(lats[endSnap.nodeA], lons[endSnap.nodeA], endSnap.snapLat, endSnap.snapLon)
        val endEdgeDistB = GeoGrid.haversineMeters(lats[endSnap.nodeB], lons[endSnap.nodeB], endSnap.snapLat, endSnap.snapLon)

        var bestGoalTotal = Double.POSITIVE_INFINITY
        var bestGoalTerminalNode = -1

        var expansions = 0
        val maxExpansions = minOf(nodeCount * 2, 350_000)

        while (queue.isNotEmpty() && expansions < maxExpansions) {
            val current = queue.poll() ?: break
            val u = current.node
            if (closed[u]) continue
            if (current.fScore >= bestGoalTotal) break

            closed[u] = true
            expansions++

            if (u == endSnap.nodeA) {
                val cand = gScore[u] + endEdgeDistA
                if (cand < bestGoalTotal) {
                    bestGoalTotal = cand
                    bestGoalTerminalNode = u
                }
            }
            if (u == endSnap.nodeB) {
                val cand = gScore[u] + endEdgeDistB
                if (cand < bestGoalTotal) {
                    bestGoalTotal = cand
                    bestGoalTerminalNode = u
                }
            }

            val startIdx = headEdge[u]
            val count = edgeCountForNode[u]
            for (k in 0 until count) {
                val eIdx = startIdx + k
                val v = edgeTargets[eIdx]
                if (closed[v]) continue
                val tentativeG = gScore[u] + edgeWeightsMeters[eIdx].toDouble()
                if (tentativeG < gScore[v]) {
                    gScore[v] = tentativeG
                    parent[v] = u
                    val h = GeoGrid.haversineMeters(lats[v], lons[v], endSnap.snapLat, endSnap.snapLon)
                    queue.add(AStarEntry(node = v, fScore = tentativeG + h))
                }
            }
        }

        if (bestGoalTerminalNode == -1 || !bestGoalTotal.isFinite()) {
            return null
        }

        val nodeChain = ArrayList<LatLonPoint>()
        var cursor = bestGoalTerminalNode
        var safety = 0
        while (cursor != -1 && safety <= nodeCount) {
            nodeChain.add(LatLonPoint(lats[cursor], lons[cursor]))
            cursor = parent[cursor]
            safety++
        }
        nodeChain.reverse()

        val fullPath = ArrayList<LatLonPoint>(nodeChain.size + 4)
        fullPath.add(LatLonPoint(startLat, startLon))
        fullPath.add(LatLonPoint(startSnap.snapLat, startSnap.snapLon))
        fullPath.addAll(nodeChain)
        fullPath.add(LatLonPoint(endSnap.snapLat, endSnap.snapLon))
        fullPath.add(LatLonPoint(endLat, endLon))

        val cleaned = deduplicatePoints(fullPath)
        val totalDistance = polylineDistanceMeters(cleaned)
        return OfflineRouteResult(
            points = cleaned,
            distanceMeters = totalDistance,
            estimatedSeconds = (totalDistance / WALKING_SPEED_MPS).toLong().coerceAtLeast(1L)
        )
    }

    private fun findNearestEdgeSnap(
        lat: Double,
        lon: Double,
        maxSnapMeters: Double
    ): EdgeSnapResult? {
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.1)
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * cosLat

        var bestDistSq = maxSnapMeters * maxSnapMeters
        var bestNodeA = -1
        var bestNodeB = -1
        var bestSnapLat = lat
        var bestSnapLon = lon

        for (u in 0 until nodeCount) {
            val uLat = lats[u]
            val uLon = lons[u]
            val startIdx = headEdge[u]
            val count = edgeCountForNode[u]

            if (count == 0) {
                val dx = (uLon - lon) * metersPerDegLon
                val dy = (uLat - lat) * metersPerDegLat
                val dSq = dx * dx + dy * dy
                if (dSq < bestDistSq) {
                    bestDistSq = dSq
                    bestNodeA = u
                    bestNodeB = u
                    bestSnapLat = uLat
                    bestSnapLon = uLon
                }
                continue
            }

            val ax = (uLon - lon) * metersPerDegLon
            val ay = (uLat - lat) * metersPerDegLat

            for (k in 0 until count) {
                val v = edgeTargets[startIdx + k]
                if (v < u) continue // each undirected street edge evaluated once

                val vLat = lats[v]
                val vLon = lons[v]
                val bx = (vLon - lon) * metersPerDegLon
                val by = (vLat - lat) * metersPerDegLat

                val abx = bx - ax
                val aby = by - ay
                val abLenSq = abx * abx + aby * aby
                val t = if (abLenSq <= 1e-6) 0.0 else (-(ax * abx + ay * aby) / abLenSq).coerceIn(0.0, 1.0)
                val px = ax + t * abx
                val py = ay + t * aby
                val distSq = px * px + py * py
                if (distSq < bestDistSq) {
                    bestDistSq = distSq
                    bestNodeA = u
                    bestNodeB = v
                    bestSnapLat = uLat + t * (vLat - uLat)
                    bestSnapLon = uLon + t * (vLon - uLon)
                }
            }
        }

        return if (bestNodeA >= 0) {
            EdgeSnapResult(
                nodeA = bestNodeA,
                nodeB = bestNodeB,
                snapLat = bestSnapLat,
                snapLon = bestSnapLon,
                distanceMeters = sqrt(bestDistSq)
            )
        } else {
            null
        }
    }

    private data class EdgeSnapResult(
        val nodeA: Int,
        val nodeB: Int,
        val snapLat: Double,
        val snapLon: Double,
        val distanceMeters: Double
    )

    private class AStarEntry(
        val node: Int,
        val fScore: Double
    ) : Comparable<AStarEntry> {
        override fun compareTo(other: AStarEntry): Int = fScore.compareTo(other.fScore)
    }

    companion object {
        private const val MAGIC = 0x54505747 // "TPWG"
        private const val FORMAT_VERSION = 1
        const val WALKING_SPEED_MPS = 1.35

        fun readFrom(inputStream: InputStream): OfflineRoadGraph {
            val input = DataInputStream(BufferedInputStream(inputStream, 16 * 1024))
            val magic = input.readInt()
            require(magic == MAGIC) { "Invalid offline walking graph file" }
            val version = input.readInt()
            require(version == FORMAT_VERSION) { "Unsupported graph version: $version" }
            val nodeCount = input.readInt().coerceAtLeast(0)
            val edgeCount = input.readInt().coerceAtLeast(0)

            val lats = DoubleArray(nodeCount)
            val lons = DoubleArray(nodeCount)
            val headEdge = IntArray(nodeCount)
            val edgeCounts = IntArray(nodeCount)
            for (i in 0 until nodeCount) {
                lats[i] = input.readDouble()
                lons[i] = input.readDouble()
                headEdge[i] = input.readInt()
                edgeCounts[i] = input.readInt()
            }
            val edgeTargets = IntArray(edgeCount)
            val edgeWeights = FloatArray(edgeCount)
            for (e in 0 until edgeCount) {
                edgeTargets[e] = input.readInt()
                edgeWeights[e] = input.readFloat()
            }
            return OfflineRoadGraph(
                lats = lats,
                lons = lons,
                headEdge = headEdge,
                edgeCountForNode = edgeCounts,
                edgeTargets = edgeTargets,
                edgeWeightsMeters = edgeWeights
            )
        }

        /**
         * Builds a walkable pedestrian graph from a single OpenStreetMap Overpass JSON response.
         */
        fun parseOverpassWalkGraph(jsonString: String): OfflineRoadGraph {
            return parseOverpassWalkGraphChunks(listOf(jsonString))
        }

        /**
         * Merges one or more Overpass JSON chunks (supporting `out skel`, `out body`, and `out geom`)
         * into a single unified walkable CSR graph without duplicating cross-boundary nodes or edges.
         */
        fun parseOverpassWalkGraphChunks(jsonStrings: List<String>): OfflineRoadGraph {
            if (jsonStrings.isEmpty()) return emptyGraph()

            val nodeLatByOsmId = HashMap<Long, Double>(4096)
            val nodeLonByOsmId = HashMap<Long, Double>(4096)
            val seenWayIds = HashSet<Long>(2048)
            val ways = ArrayList<LongArray>(2048)

            for (jsonString in jsonStrings) {
                if (jsonString.isBlank()) continue
                val root = JSONObject(jsonString)
                val elements = root.optJSONArray("elements") ?: continue

                for (i in 0 until elements.length()) {
                    val el = elements.optJSONObject(i) ?: continue
                    when (el.optString("type")) {
                        "node" -> {
                            val id = el.optLong("id", -1L)
                            if (id >= 0L && el.has("lat") && el.has("lon")) {
                                nodeLatByOsmId[id] = el.optDouble("lat")
                                nodeLonByOsmId[id] = el.optDouble("lon")
                            }
                        }
                        "way" -> {
                            val wayId = el.optLong("id", -1L)
                            if (wayId >= 0L && !seenWayIds.add(wayId)) continue
                            val tags = el.optJSONObject("tags")
                            if (!isWalkableWay(tags)) continue
                            val nds = el.optJSONArray("nodes") ?: continue
                            if (nds.length() < 2) continue
                            val geom = el.optJSONArray("geometry")
                            val arr = LongArray(nds.length())
                            for (k in 0 until nds.length()) {
                                val nid = nds.optLong(k, -1L)
                                arr[k] = nid
                                if (nid >= 0L && geom != null && k < geom.length()) {
                                    val gObj = geom.optJSONObject(k)
                                    if (gObj != null && gObj.has("lat") && gObj.has("lon")) {
                                        nodeLatByOsmId.putIfAbsent(nid, gObj.optDouble("lat"))
                                        nodeLonByOsmId.putIfAbsent(nid, gObj.optDouble("lon"))
                                    }
                                }
                            }
                            ways.add(arr)
                        }
                    }
                }
            }

            if (ways.isEmpty()) return emptyGraph()

            val compactIdxByOsmId = HashMap<Long, Int>(nodeLatByOsmId.size)
            val compactLats = ArrayList<Double>(nodeLatByOsmId.size)
            val compactLons = ArrayList<Double>(nodeLatByOsmId.size)
            val adjLists = ArrayList<ArrayList<Pair<Int, Float>>>(nodeLatByOsmId.size)

            fun indexFor(osmId: Long): Int? {
                val existing = compactIdxByOsmId[osmId]
                if (existing != null) return existing
                val lat = nodeLatByOsmId[osmId] ?: return null
                val lon = nodeLonByOsmId[osmId] ?: return null
                val newIdx = compactLats.size
                compactIdxByOsmId[osmId] = newIdx
                compactLats.add(lat)
                compactLons.add(lon)
                adjLists.add(ArrayList(4))
                return newIdx
            }

            for (wayNodes in ways) {
                var prevIdx: Int? = null
                for (osmId in wayNodes) {
                    val curIdx = indexFor(osmId)
                    if (curIdx == null) {
                        prevIdx = null
                        continue
                    }
                    if (prevIdx != null && prevIdx != curIdx) {
                        val dist = GeoGrid.haversineMeters(
                            compactLats[prevIdx],
                            compactLons[prevIdx],
                            compactLats[curIdx],
                            compactLons[curIdx]
                        ).toFloat().coerceAtLeast(0.5f)
                        addUniqueEdge(adjLists[prevIdx], curIdx, dist)
                        addUniqueEdge(adjLists[curIdx], prevIdx, dist)
                    }
                    prevIdx = curIdx
                }
            }

            val nCount = compactLats.size
            if (nCount == 0) return emptyGraph()

            val lats = DoubleArray(nCount) { compactLats[it] }
            val lons = DoubleArray(nCount) { compactLons[it] }
            val headEdge = IntArray(nCount)
            val edgeCountForNode = IntArray(nCount)

            var totalEdges = 0
            for (u in 0 until nCount) {
                headEdge[u] = totalEdges
                val deg = adjLists[u].size
                edgeCountForNode[u] = deg
                totalEdges += deg
            }

            val edgeTargets = IntArray(totalEdges)
            val edgeWeights = FloatArray(totalEdges)
            var cursor = 0
            for (u in 0 until nCount) {
                for ((target, weight) in adjLists[u]) {
                    edgeTargets[cursor] = target
                    edgeWeights[cursor] = weight
                    cursor++
                }
            }

            return OfflineRoadGraph(
                lats = lats,
                lons = lons,
                headEdge = headEdge,
                edgeCountForNode = edgeCountForNode,
                edgeTargets = edgeTargets,
                edgeWeightsMeters = edgeWeights
            )
        }

        private fun addUniqueEdge(list: ArrayList<Pair<Int, Float>>, target: Int, dist: Float) {
            for (i in list.indices) {
                if (list[i].first == target) {
                    if (dist < list[i].second) {
                        list[i] = target to dist
                    }
                    return
                }
            }
            list.add(target to dist)
        }

        private fun isWalkableWay(tags: JSONObject?): Boolean {
            if (tags == null) return true
            val foot = tags.optString("foot").lowercase()
            if (foot == "no" || foot == "private") return false
            val access = tags.optString("access").lowercase()
            if (access == "no" || access == "private") return false
            val highway = tags.optString("highway").lowercase()
            if (highway in NON_WALKABLE_HIGHWAYS) return false
            return true
        }

        private val NON_WALKABLE_HIGHWAYS = setOf(
            "motorway",
            "motorway_link",
            "trunk",
            "trunk_link",
            "busway"
        )

        fun emptyGraph(): OfflineRoadGraph = OfflineRoadGraph(
            lats = DoubleArray(0),
            lons = DoubleArray(0),
            headEdge = IntArray(0),
            edgeCountForNode = IntArray(0),
            edgeTargets = IntArray(0),
            edgeWeightsMeters = FloatArray(0)
        )

        fun polylineDistanceMeters(points: List<LatLonPoint>): Double {
            if (points.size < 2) return 0.0
            var total = 0.0
            for (i in 1 until points.size) {
                total += GeoGrid.haversineMeters(
                    points[i - 1].latitude,
                    points[i - 1].longitude,
                    points[i].latitude,
                    points[i].longitude
                )
            }
            return total
        }

        fun deduplicatePoints(points: List<LatLonPoint>): List<LatLonPoint> {
            if (points.size <= 2) return points
            val out = ArrayList<LatLonPoint>(points.size)
            out.add(points.first())
            for (i in 1 until points.lastIndex) {
                val prev = out.last()
                val cur = points[i]
                if (GeoGrid.haversineMeters(prev.latitude, prev.longitude, cur.latitude, cur.longitude) >= 0.8) {
                    out.add(cur)
                }
            }
            val last = points.last()
            if (GeoGrid.haversineMeters(out.last().latitude, out.last().longitude, last.latitude, last.longitude) >= 0.4 || out.size == 1) {
                out.add(last)
            }
            return out
        }
    }
}
