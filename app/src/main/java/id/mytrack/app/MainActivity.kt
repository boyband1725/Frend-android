package id.mytrack.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.location.Geocoder
import android.os.Bundle
import org.osmdroid.config.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.maps.model.LatLng
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker as OsmMarker
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Polyline
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.Locale
import java.util.concurrent.TimeUnit

private val Navy = Color(0xFF123B70)
private val Blue = Color(0xFF2878E5)
private val Pale = Color(0xFFF3F7FC)

private data class AisShip(
    val mmsi: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val speedKnots: Double,
    val course: Double,
    val shipType: Int,
    val imo: Long
)

private fun shipTypeLabel(type: Int): String = when (type) {
    in 60..69 -> "Kapal penumpang"
    in 70..79 -> "Kapal kargo"
    in 80..89 -> "Kapal tanker"
    in 30..39 -> "Kapal khusus"
    else -> "Kapal AIS"
}

private fun shipMarkerIcon(context: android.content.Context, shipType: Int): BitmapDrawable {
    val bitmap = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val bodyColor = when (shipType) {
        in 60..69 -> android.graphics.Color.rgb(238, 177, 45)
        in 70..79 -> android.graphics.Color.rgb(42, 111, 205)
        in 80..89 -> android.graphics.Color.rgb(231, 126, 42)
        else -> android.graphics.Color.rgb(92, 111, 128)
    }
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(56f, 56f, 49f, paint)
    paint.color = android.graphics.Color.rgb(45, 54, 66)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 4f
    canvas.drawCircle(56f, 56f, 47f, paint)
    paint.style = Paint.Style.FILL
    paint.color = bodyColor
    val hull = Path().apply {
        moveTo(56f, 9f)
        lineTo(78f, 39f)
        lineTo(73f, 82f)
        quadTo(56f, 101f, 39f, 82f)
        lineTo(34f, 39f)
        close()
    }
    canvas.drawPath(hull, paint)
    paint.color = android.graphics.Color.WHITE
    canvas.drawRoundRect(RectF(45f, 39f, 67f, 63f), 5f, 5f, paint)
    paint.color = bodyColor
    canvas.drawRect(RectF(50f, 44f, 62f, 49f), paint)
    paint.color = android.graphics.Color.WHITE
    canvas.drawRect(RectF(51f, 53f, 61f, 58f), paint)
    return BitmapDrawable(context.resources, bitmap)
}

private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadiusKm = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
        kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
    return earthRadiusKm * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Blue, background = Pale)) { MytrackScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MytrackScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fused = remember { LocationServices.getFusedLocationProviderClient(context) }
    val prefs = remember { context.getSharedPreferences("mytrack", 0) }
    val deviceId = remember { prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() } }
    var myLocation by remember { mutableStateOf<LatLng?>(null) }
    var myPlaceName by remember { mutableStateOf("Mencari nama lokasi…") }
    var searchQuery by remember { mutableStateOf("") }
    var searchStatus by remember { mutableStateOf("") }
    var searchedPlace by remember { mutableStateOf<LatLng?>(null) }
    var routePoints by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var routeInstruction by remember { mutableStateOf("") }
    var routeSummary by remember { mutableStateOf("") }
    var routeDestinationName by remember { mutableStateOf("") }
    var navigationActive by remember { mutableStateOf(false) }
    var routeLoading by remember { mutableStateOf(false) }
    var selectedMenu by remember { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var savedLocation by remember { mutableStateOf(prefs.getString("saved_location", "") ?: "") }
    var partnerLocation by remember { mutableStateOf<LatLng?>(null) }
    var pairCode by remember { mutableStateOf(prefs.getString("pair_code", "") ?: "") }
    var codeInput by remember { mutableStateOf(pairCode) }
    var sharing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Lokasi belum diaktifkan") }
    var permissionGranted by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    ) }
    var lastPartnerUpdate by remember { mutableStateOf("Belum ada lokasi pasangan") }
    var radarEnabled by remember { mutableStateOf(false) }
    var aisShips by remember { mutableStateOf<List<AisShip>>(emptyList()) }
    var radarStatus by remember { mutableStateOf("Radar kapal nonaktif") }
    var aisAccessToken by remember { mutableStateOf<String?>(null) }
    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(65, TimeUnit.SECONDS)
            .build()
    }
    LaunchedEffect(Unit) { Configuration.getInstance().userAgentValue = context.packageName }

    fun requestLocation() {
        if (!permissionGranted) return
        try {
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        val point = LatLng(loc.latitude, loc.longitude)
                        myLocation = point
                        scope.launch {
                            myPlaceName = try {
                                withContext(Dispatchers.IO) {
                                    @Suppress("DEPRECATION")
                                    Geocoder(context, Locale("id", "ID")).getFromLocation(loc.latitude, loc.longitude, 1)
                                        ?.firstOrNull()?.getAddressLine(0) ?: "Nama tempat belum tersedia"
                                }
                            } catch (_: Exception) { "Nama tempat belum tersedia" }
                        }
                    } else status = "GPS belum mendapatkan lokasi. Coba di luar ruangan."
                }
                .addOnFailureListener { status = "Gagal mengambil lokasi: ${it.localizedMessage}" }
        } catch (_: SecurityException) { status = "Izin lokasi belum diberikan" }
    }

    suspend fun buildRoute(destination: LatLng, destinationName: String) {
        val origin = myLocation
        if (origin == null) {
            searchStatus = "Lokasi GPS belum tersedia. Tekan tombol lokasi lalu coba lagi."
            return
        }
        routeLoading = true
        searchStatus = "Menghitung rute…"
        try {
            val url = "https://router.project-osrm.org/route/v1/driving/${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}?overview=full&geometries=geojson&steps=true"
            val result = withContext(Dispatchers.IO) {
                val req = Request.Builder().url(url).header("User-Agent", "id.mytrack.app").get().build()
                client.newCall(req).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw IllegalStateException("Layanan rute HTTP ${response.code}")
                    JSONObject(body)
                }
            }
            val route = result.optJSONArray("routes")?.optJSONObject(0)
                ?: throw IllegalStateException("Rute kendaraan tidak ditemukan")
            val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
            routePoints = (0 until coords.length()).mapNotNull { i ->
                val pair = coords.optJSONArray(i) ?: return@mapNotNull null
                if (pair.length() < 2) null else GeoPoint(pair.getDouble(1), pair.getDouble(0))
            }
            val km = route.optDouble("distance", 0.0) / 1000.0
            val minutes = route.optDouble("duration", 0.0) / 60.0
            routeSummary = String.format(Locale("id", "ID"), "%.1f km • sekitar %.0f menit", km, minutes)
            routeDestinationName = destinationName
            val steps = route.optJSONArray("legs")?.optJSONObject(0)?.optJSONArray("steps")
            val first = steps?.optJSONObject(0)
            val maneuver = first?.optJSONObject("maneuver")
            val road = first?.optString("name", "").orEmpty()
            val type = maneuver?.optString("type", "depart").orEmpty()
            val modifier = maneuver?.optString("modifier", "").orEmpty()
            routeInstruction = when (type) {
                "depart" -> "Mulai perjalanan"
                "arrive" -> "Anda tiba di tujuan"
                "turn" -> "Belok ${modifier.ifBlank { "sesuai petunjuk" }}"
                "roundabout", "rotary" -> "Masuk bundaran"
                "continue" -> "Terus ikuti jalan"
                "new name" -> "Lanjut ke jalan berikutnya"
                else -> "Ikuti rute"
            } + if (road.isNotBlank()) " ke $road" else ""
            navigationActive = false
            searchStatus = "Rute ditemukan: $routeSummary"
            selectedMenu = null
        } catch (e: Exception) {
            routePoints = emptyList()
            searchStatus = "Gagal membuat rute: ${e.localizedMessage ?: "periksa koneksi internet"}"
        } finally {
            routeLoading = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (permissionGranted) requestLocation() else status = "Izin lokasi diperlukan untuk berbagi lokasi"
    }

    LaunchedEffect(permissionGranted) { if (permissionGranted) requestLocation() }
    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    LaunchedEffect(radarEnabled, myLocation) {
        while (radarEnabled) {
            val point = myLocation
            if (point == null) {
                radarStatus = "Menunggu lokasi GPS untuk radar"
                requestLocation()
            } else if (BuildConfig.SUPABASE_URL.startsWith("https://") &&
                !BuildConfig.SUPABASE_URL.contains("YOUR_PROJECT") &&
                !BuildConfig.SUPABASE_ANON_KEY.startsWith("YOUR_")) {
                try {
                    // Edge Function memakai verify_jwt=true. Buat sesi Supabase anonim
                    // agar permintaan AIS membawa access token JWT, bukan publishable key.
                    val accessToken = aisAccessToken ?: withContext(Dispatchers.IO) {
                        val authBody = JSONObject().put("data", JSONObject())
                            .toString().toRequestBody("application/json".toMediaType())
                        val authRequest = Request.Builder()
                            .url("${BuildConfig.SUPABASE_URL}/auth/v1/signup")
                            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                            .addHeader("Content-Type", "application/json")
                            .post(authBody).build()
                        client.newCall(authRequest).execute().use { response ->
                            val body = response.body?.string().orEmpty()
                            if (!response.isSuccessful) {
                                throw IllegalStateException("Login anonim HTTP ${response.code}: $body")
                            }
                            JSONObject(body).optString("access_token").takeIf { it.isNotBlank() }
                                ?: throw IllegalStateException("Supabase tidak mengirim access token. Aktifkan Anonymous Sign-Ins di Supabase Auth.")
                        }
                    }
                    aisAccessToken = accessToken

                    val radarRequest = Request.Builder()
                        .url("${BuildConfig.SUPABASE_URL}/functions/v1/ais-radar?lat=${point.latitude}&lon=${point.longitude}&radius=20")
                        .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                        .addHeader("Authorization", "Bearer $accessToken")
                        .get().build()
                    val radarResult = withContext(Dispatchers.IO) {
                        client.newCall(radarRequest).execute().use { response ->
                            val body = response.body?.string().orEmpty()
                            if (response.code == 401) {
                                aisAccessToken = null
                                throw IllegalStateException("Sesi AIS kedaluwarsa atau Anonymous Sign-Ins belum aktif (HTTP 401)")
                            }
                            if (!response.isSuccessful) throw IllegalStateException("AIS server HTTP ${response.code}: $body")
                            JSONObject(body)
                        }
                    }
                    val array = radarResult.optJSONArray("ships") ?: JSONArray()
                    aisShips = (0 until array.length()).mapNotNull { index ->
                        val item = array.optJSONObject(index) ?: return@mapNotNull null
                        val lat = item.optDouble("latitude", Double.NaN)
                        val lon = item.optDouble("longitude", Double.NaN)
                        if (!lat.isFinite() || !lon.isFinite() || kotlin.math.abs(lat) > 90 || kotlin.math.abs(lon) > 180) null else AisShip(
                            mmsi = item.optLong("mmsi"),
                            name = item.optString("name", "Nama kapal belum diterima").trim(),
                            latitude = lat,
                            longitude = lon,
                            speedKnots = item.optDouble("speedKnots", 0.0),
                            course = item.optDouble("course", 0.0),
                            shipType = item.optInt("shipType", 0),
                            imo = item.optLong("imo", 0L)
                        )
                    }.distinctBy { it.mmsi }
                        .sortedBy { ship -> distanceKm(point.latitude, point.longitude, ship.latitude, ship.longitude) }
                    val streamError = radarResult.optString("streamError", "")
                    val message = radarResult.optString("message", "")
                    val confirmed = radarResult.optBoolean("subscriptionConfirmed", false)
                    val compressed = radarResult.optBoolean("compressionEnabled", false)
                    val counts = radarResult.optJSONObject("messageCounts")
                    val positionPackets = (counts?.optInt("PositionReport", 0) ?: 0) +
                        (counts?.optInt("StandardClassBPositionReport", 0) ?: 0) +
                        (counts?.optInt("ExtendedClassBPositionReport", 0) ?: 0)
                    radarStatus = when {
                        aisShips.isNotEmpty() -> "${aisShips.size} kapal AIS terdeteksi • radius 20 km"
                        streamError.isNotBlank() && streamError != "null" -> "Sumber AIS bermasalah: $streamError"
                        !confirmed -> message.ifBlank { "AISStream belum mengonfirmasi koneksi" }
                        !compressed -> "Koneksi AIS aktif, tetapi kompresi data belum aktif"
                        positionPackets == 0 -> "AIS tersambung dan terkompresi; belum ada laporan posisi kapal dalam sampel 45 detik"
                        else -> message.ifBlank { "Paket posisi diterima, menunggu koordinat kapal" }
                    }
                } catch (e: Exception) {
                    val cause = e.localizedMessage ?: "periksa koneksi dan konfigurasi"
                    radarStatus = if (e is java.net.SocketTimeoutException) {
                        "Waktu tunggu AIS habis. Periksa koneksi server dan AISStream."
                    } else "Radar gagal: $cause"
                }
            } else {
                radarStatus = "Konfigurasi Supabase belum benar"
            }
            delay(30000)
        }
    }

    LaunchedEffect(sharing, pairCode, myLocation) {
        while (sharing && pairCode.length >= 6) {
            val point = myLocation
            if (point != null && BuildConfig.SUPABASE_URL.startsWith("https://") && !BuildConfig.SUPABASE_URL.contains("YOUR_PROJECT")) {
                try {
                    val json = JSONObject().put("pair_code", pairCode.trim().uppercase())
                        .put("user_id", deviceId).put("latitude", point.latitude)
                        .put("longitude", point.longitude).put("updated_at", java.time.Instant.now().toString())
                    val body = json.toString().toRequestBody("application/json".toMediaType())
                    val req = Request.Builder()
                        .url("${BuildConfig.SUPABASE_URL}/rest/v1/shared_locations?on_conflict=pair_code,user_id")
                        .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                        .addHeader("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                        .addHeader("Prefer", "resolution=merge-duplicates")
                        .post(body).build()
                    val uploadResult = withContext(Dispatchers.IO) {
                        client.newCall(req).execute().use { response -> response.isSuccessful to response.code }
                    }
                    status = if (uploadResult.first) "Berbagi lokasi aktif" else "Gagal mengirim lokasi (${uploadResult.second})"
                    val get = Request.Builder()
                        .url("${BuildConfig.SUPABASE_URL}/rest/v1/shared_locations?select=user_id,latitude,longitude,updated_at&pair_code=eq.${java.net.URLEncoder.encode(pairCode.trim().uppercase(), "UTF-8")}&user_id=neq.$deviceId&order=updated_at.desc&limit=1")
                        .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                        .addHeader("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}").get().build()
                    val remotePoint = withContext(Dispatchers.IO) {
                        client.newCall(get).execute().use { response ->
                            if (!response.isSuccessful) null else {
                                val arr = JSONArray(response.body?.string() ?: "[]")
                                if (arr.length() == 0) null else {
                                    val item = arr.getJSONObject(0)
                                    LatLng(item.getDouble("latitude"), item.getDouble("longitude"))
                                }
                            }
                        }
                    }
                    partnerLocation = remotePoint
                    lastPartnerUpdate = if (remotePoint == null) "Menunggu pasangan membagikan lokasi" else "Lokasi pasangan diperbarui"
                } catch (e: Exception) { status = "Koneksi gagal: ${e.localizedMessage ?: "periksa internet"}" }
            } else if (point != null) status = "Isi konfigurasi Supabase untuk mengaktifkan sinkronisasi"
            delay(5000)
            if (permissionGranted) requestLocation()
        }
    }

    Box(Modifier.fillMaxSize().background(Pale)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    setBuiltInZoomControls(false)
                    controller.setZoom(12.0)
                    controller.setCenter(GeoPoint(-6.2, 106.816666))
                    tag = ""
                }
            },
            update = { map ->
                map.overlays.clear()
                if (routePoints.size > 1) {
                    val routeLine = Polyline(map).apply {
                        setPoints(routePoints)
                        outlinePaint.color = android.graphics.Color.rgb(30, 110, 230)
                        outlinePaint.strokeWidth = 14f
                    }
                    map.overlays.add(routeLine)
                }
                fun addPin(point: LatLng, title: String, detail: String) {
                    val marker = OsmMarker(map)
                    marker.position = GeoPoint(point.latitude, point.longitude)
                    marker.title = title
                    marker.snippet = detail
                    marker.setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_BOTTOM)
                    map.overlays.add(marker)
                }
                myLocation?.let { addPin(it, "Lokasi saya", myPlaceName) }
                searchedPlace?.let { addPin(it, searchQuery.ifBlank { "Tempat dicari" }, "Hasil pencarian") }
                partnerLocation?.let { addPin(it, "Pasangan", lastPartnerUpdate) }
                aisShips.forEach { ship ->
                    val marker = OsmMarker(map).apply {
                        position = GeoPoint(ship.latitude, ship.longitude)
                        title = ship.name.ifBlank { "Kapal ${ship.mmsi}" }
                        snippet = buildString {
                            append("${shipTypeLabel(ship.shipType)} • ")
                            append("MMSI ${ship.mmsi}")
                            if (ship.imo > 0) append(" • IMO ${ship.imo}")
                            append(" • ${String.format(Locale.US, "%.1f kn", ship.speedKnots)}")
                            append(" • arah ${ship.course.toInt()}°")
                        }
                        icon = shipMarkerIcon(context, ship.shipType)
                        setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_CENTER)
                        rotation = (ship.course % 360.0).toFloat()
                    }
                    map.overlays.add(marker)
                }
                if (routePoints.isNotEmpty()) {
                    val start = routePoints.first()
                    val end = routePoints.last()
                    val startMarker = OsmMarker(map).apply {
                        position = start
                        title = "Mulai"
                        setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_BOTTOM)
                    }
                    val endMarker = OsmMarker(map).apply {
                        position = end
                        title = "Tujuan: $routeDestinationName"
                        setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_BOTTOM)
                    }
                    map.overlays.add(startMarker)
                    map.overlays.add(endMarker)
                    val routeKey = "route:${routePoints.size}:${start.latitude}:${end.longitude}"
                    if (map.tag != routeKey) {
                        map.zoomToBoundingBox(org.osmdroid.util.BoundingBox.fromGeoPoints(routePoints), true, 72)
                        map.tag = routeKey
                    }
                } else {
                    val centerKey = myLocation?.let { "loc:${it.latitude},${it.longitude}" }.orEmpty()
                    if (centerKey.isNotEmpty() && map.tag != centerKey) {
                        map.controller.animateTo(GeoPoint(myLocation!!.latitude, myLocation!!.longitude))
                        map.controller.setZoom(15.0)
                        map.tag = centerKey
                    }
                }
                map.overlays.add(CopyrightOverlay(context))
                map.invalidate()
            }
        )

        // Floating header: the map remains visible behind every control.
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
            shape = RoundedCornerShape(22.dp), color = Color.White.copy(alpha = 0.96f), shadowElevation = 8.dp
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Navy) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("MYtrack", fontWeight = FontWeight.Bold, color = Navy, style = MaterialTheme.typography.titleLarge)
                    Text("Berbagi lokasi bersama", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { requestLocation() }) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Lokasi saya", tint = Blue)
                }
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 18.dp, top = 82.dp),
            shape = RoundedCornerShape(24.dp), color = Color.White, shadowElevation = 5.dp
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(if (sharing) Color(0xFF22A06B) else Color.Gray, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(if (sharing) "Berbagi aktif" else "Berbagi dijeda", color = Navy, style = MaterialTheme.typography.labelMedium)
            }
        }

        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SmallFloatingActionButton(onClick = { requestLocation() }, containerColor = Color.White, contentColor = Blue) {
                Icon(Icons.Default.MyLocation, contentDescription = "Ke lokasi saya")
            }
            SmallFloatingActionButton(onClick = { selectedMenu = "Pencarian lokasi" }, containerColor = Color.White, contentColor = Blue) {
                Icon(Icons.Default.Search, contentDescription = "Cari lokasi")
            }
        }

        if (myLocation == null) {
            Card(Modifier.align(Alignment.Center).padding(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Text("Menunggu GPS mendapatkan lokasi…", Modifier.padding(16.dp), color = Navy)
            }
        }

        // Compact two-column menu floats over the map instead of consuming map height.
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 20.dp, bottomEnd = 20.dp),
            color = Color.White.copy(alpha = 0.97f), shadowElevation = 12.dp
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(42.dp).height(5.dp).background(Color(0xFF9AA3B2), RoundedCornerShape(8.dp)).align(Alignment.CenterHorizontally))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MenuTile("Berbagi Lokasi", Icons.Default.LocationOn, Modifier.weight(1f)) { selectedMenu = "Berbagi Lokasi" }
                    MenuTile("Pasangan", Icons.Default.People, Modifier.weight(1f)) { selectedMenu = "Pasangan" }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MenuTile("Radar Kapal AIS", Icons.Default.DirectionsBoat, Modifier.weight(1f)) { selectedMenu = "Radar Kapal AIS" }
                    MenuTile("Pengaturan", Icons.Default.Settings, Modifier.weight(1f)) { selectedMenu = "Pengaturan" }
                }
            }
        }
    }

    if (navigationActive && routePoints.isNotEmpty()) {
        Surface(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(top = 150.dp, start = 14.dp, end = 14.dp),
            shape = RoundedCornerShape(18.dp), color = Color.White, shadowElevation = 8.dp
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(routeInstruction, color = Navy, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("$routeDestinationName • $routeSummary", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                Text("Ikuti garis biru pada peta. Petunjuk belokan lanjutan dan suara belum tersedia.", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { navigationActive = false }) { Text("Akhiri navigasi") }
            }
        }
    }


    if (menuExpanded) {
        ModalBottomSheet(
            onDismissRequest = { menuExpanded = false },
            containerColor = Color.White,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Menu MYtrack", color = Navy, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Pilih fitur yang ingin digunakan", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                ListItem(headlineContent = { Text("Peta utama") }, supportingContent = { Text("Kembali melihat peta dan posisi saat ini") }, leadingContent = { Icon(Icons.Default.Map, contentDescription = null, tint = Blue) }, modifier = Modifier.fillMaxWidth().clickable { menuExpanded = false })
                ListItem(headlineContent = { Text("Pencarian lokasi") }, supportingContent = { Text("Cari alamat, tempat, pelabuhan, atau kota") }, leadingContent = { Icon(Icons.Default.Search, contentDescription = null, tint = Blue) }, modifier = Modifier.fillMaxWidth().clickable { menuExpanded = false; selectedMenu = "Pencarian lokasi" })
                ListItem(headlineContent = { Text("Navigasi / rute") }, supportingContent = { Text(if (routePoints.isNotEmpty()) routeSummary.ifBlank { "Lihat rute yang sudah dibuat" } else "Cari tujuan untuk membuat rute") }, leadingContent = { Icon(Icons.Default.Navigation, contentDescription = null, tint = Blue) }, modifier = Modifier.fillMaxWidth().clickable { menuExpanded = false; if (routePoints.isNotEmpty()) navigationActive = true else selectedMenu = "Pencarian lokasi" })
                ListItem(headlineContent = { Text("Berbagi lokasi") }, supportingContent = { Text("Aktifkan berbagi lokasi dengan kode pasangan") }, leadingContent = { Icon(Icons.Default.LocationOn, contentDescription = null, tint = Blue) }, modifier = Modifier.fillMaxWidth().clickable { menuExpanded = false; selectedMenu = "Berbagi Lokasi" })
                ListItem(headlineContent = { Text("Pasangan") }, supportingContent = { Text("Lihat lokasi perangkat pasangan") }, leadingContent = { Icon(Icons.Default.People, contentDescription = null, tint = Blue) }, modifier = Modifier.fillMaxWidth().clickable { menuExpanded = false; selectedMenu = "Pasangan" })
                ListItem(headlineContent = { Text("Radar kapal AIS") }, supportingContent = { Text("Pantau kapal yang mengirim data AIS") }, leadingContent = { Icon(Icons.Default.DirectionsBoat, contentDescription = null, tint = Blue) }, modifier = Modifier.fillMaxWidth().clickable { menuExpanded = false; selectedMenu = "Radar Kapal AIS" })
                Divider()
                Text("Akses cepat", color = Navy, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                ListItem(headlineContent = { Text("Simpan lokasi saya") }, supportingContent = { Text("Simpan koordinat saat ini di perangkat") }, leadingContent = { Icon(Icons.Default.BookmarkAdd, contentDescription = null, tint = Blue) }, modifier = Modifier.fillMaxWidth().clickable {
                    menuExpanded = false
                    val p = myLocation
                    if (p != null) {
                        savedLocation = "${p.latitude},${p.longitude}"
                        prefs.edit().putString("saved_location", savedLocation).apply()
                        android.widget.Toast.makeText(context, "Lokasi berhasil disimpan", android.widget.Toast.LENGTH_SHORT).show()
                    } else android.widget.Toast.makeText(context, "Lokasi GPS belum tersedia", android.widget.Toast.LENGTH_SHORT).show()
                })
                ListItem(headlineContent = { Text("Lihat lokasi tersimpan") }, supportingContent = { Text(if (savedLocation.isBlank()) "Belum ada lokasi tersimpan" else savedLocation) }, leadingContent = { Icon(Icons.Default.Bookmarks, contentDescription = null, tint = Blue) }, modifier = Modifier.fillMaxWidth().clickable {
                    menuExpanded = false
                    val parts = savedLocation.split(",")
                    val lat = parts.getOrNull(0)?.toDoubleOrNull()
                    val lon = parts.getOrNull(1)?.toDoubleOrNull()
                    if (lat != null && lon != null) {
                        searchedPlace = LatLng(lat, lon)
                        searchQuery = "Lokasi tersimpan"
                    } else android.widget.Toast.makeText(context, "Belum ada lokasi tersimpan", android.widget.Toast.LENGTH_SHORT).show()
                })
                ListItem(headlineContent = { Text("Bagikan koordinat") }, supportingContent = { Text("Kirim lokasi melalui aplikasi lain") }, leadingContent = { Icon(Icons.Default.Share, contentDescription = null, tint = Blue) }, modifier = Modifier.fillMaxWidth().clickable {
                    menuExpanded = false
                    val p = myLocation
                    if (p != null) {
                        val shareText = "Lokasi saya: https://maps.google.com/?q=${p.latitude},${p.longitude}"
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                        context.startActivity(android.content.Intent.createChooser(send, "Bagikan lokasi"))
                    } else android.widget.Toast.makeText(context, "Lokasi GPS belum tersedia", android.widget.Toast.LENGTH_SHORT).show()
                })
                ListItem(headlineContent = { Text("Salin koordinat") }, supportingContent = { Text("Salin latitude dan longitude") }, leadingContent = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Blue) }, modifier = Modifier.fillMaxWidth().clickable {
                    menuExpanded = false
                    val p = myLocation
                    if (p != null) {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Koordinat MYtrack", "${p.latitude}, ${p.longitude}"))
                        android.widget.Toast.makeText(context, "Koordinat disalin", android.widget.Toast.LENGTH_SHORT).show()
                    } else android.widget.Toast.makeText(context, "Lokasi GPS belum tersedia", android.widget.Toast.LENGTH_SHORT).show()
                })
                ListItem(headlineContent = { Text("Pengaturan") }, supportingContent = { Text("Izin lokasi dan informasi perangkat") }, leadingContent = { Icon(Icons.Default.Settings, contentDescription = null, tint = Blue) }, modifier = Modifier.fillMaxWidth().clickable { menuExpanded = false; selectedMenu = "Pengaturan" })
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    if (selectedMenu != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedMenu = null },
            containerColor = Color.White,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(selectedMenu ?: "", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy)
                    IconButton(onClick = { selectedMenu = null }) { Icon(Icons.Default.Close, contentDescription = "Tutup") }
                }

                when (selectedMenu) {
                    "Berbagi Lokasi" -> {
                        Text("Masukkan kode yang sama pada perangkat pasangan.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = codeInput, onValueChange = { codeInput = it.uppercase().take(12) },
                            modifier = Modifier.fillMaxWidth(), label = { Text("Kode pasangan (minimal 6 karakter)") },
                            singleLine = true, leadingIcon = { Icon(Icons.Default.Group, contentDescription = null) }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                                if (codeInput.trim().length >= 6) {
                                    pairCode = codeInput.trim().uppercase()
                                    prefs.edit().putString("pair_code", pairCode).apply()
                                    status = "Kode pasangan tersimpan"
                                } else status = "Kode minimal 6 karakter"
                            }) { Text("Simpan kode") }
                            Button(modifier = Modifier.weight(1f), onClick = {
                                if (!permissionGranted) permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                else if (pairCode.length < 6) status = "Simpan kode pasangan terlebih dahulu"
                                else { sharing = !sharing; status = if (sharing) "Mengaktifkan berbagi..." else "Berbagi lokasi dihentikan" }
                            }) {
                                Icon(if (sharing) Icons.Default.Pause else Icons.Default.ShareLocation, contentDescription = null)
                                Spacer(Modifier.width(6.dp)); Text(if (sharing) "Hentikan" else "Mulai berbagi")
                            }
                        }
                        if (!permissionGranted) {
                            Button(modifier = Modifier.fillMaxWidth(), onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) {
                                Icon(Icons.Default.MyLocation, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Aktifkan izin lokasi")
                            }
                        }
                        Text(status, color = Blue, style = MaterialTheme.typography.bodySmall)
                        Text(lastPartnerUpdate, color = Color.DarkGray, style = MaterialTheme.typography.bodyMedium)
                        Text("Lokasi dibagikan saat tombol Mulai berbagi aktif dan aplikasi sedang terbuka.", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    }
                    "Pasangan" -> {
                        Text(if (partnerLocation == null) "Belum ada lokasi pasangan. Pastikan kedua perangkat memakai kode pasangan yang sama dan berbagi lokasi aktif." else lastPartnerUpdate, color = Color.Gray)
                        partnerLocation?.let { Text("Koordinat: ${it.latitude}, ${it.longitude}", color = Navy) }
                        Text("Kode tersimpan: ${if (pairCode.isBlank()) "Belum diatur" else pairCode}", color = Navy)
                    }
                    "Radar Kapal AIS" -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Pantau kapal di sekitar", fontWeight = FontWeight.SemiBold, color = Navy)
                                Text(radarStatus, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = radarEnabled, onCheckedChange = {
                                radarEnabled = it
                                if (!it) { aisShips = emptyList(); radarStatus = "Radar kapal nonaktif" }
                                else radarStatus = "Menghubungkan ke AISStream..."
                            })
                        }
                        Text("Kapal terdeteksi: ${aisShips.size}", fontWeight = FontWeight.Bold, color = Navy)
                        if (aisShips.isEmpty()) Text("Belum ada data kapal AIS yang diterima.", color = Color.Gray)
                        else aisShips.forEach { ship ->
                            ListItem(
                                headlineContent = { Text(ship.name.ifBlank { "Nama kapal belum tersedia" }) },
                                supportingContent = { Text("${shipTypeLabel(ship.shipType)} • MMSI ${ship.mmsi} • ${String.format(Locale.US, "%.1f kn", ship.speedKnots)} • arah ${ship.course.toInt()}°") },
                                leadingContent = { Icon(Icons.Default.DirectionsBoat, contentDescription = null, tint = when (ship.shipType) { in 80..89 -> Color(0xFFE77E2A); in 60..69 -> Color(0xFFEEB12D); else -> Blue }) }
                            )
                        }
                        Text("Cakupan bergantung pada penerima AIS di sekitar dan layanan AIS yang tersedia.", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    }
                    "Pencarian lokasi" -> {
                        OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Cari nama tempat atau alamat") }, singleLine = true)
                        Button(modifier = Modifier.fillMaxWidth(), onClick = {
                            if (searchQuery.isBlank()) searchStatus = "Masukkan nama tempat terlebih dahulu"
                            else scope.launch {
                                searchStatus = "Mencari lokasi…"
                                try {
                                    val results = withContext(Dispatchers.IO) {
                                        @Suppress("DEPRECATION")
                                        Geocoder(context, Locale("id", "ID")).getFromLocationName(searchQuery, 1)
                                    }
                                    val found = results?.firstOrNull()
                                    if (found != null) {
                                        searchedPlace = LatLng(found.latitude, found.longitude)
                                        searchStatus = "Lokasi ditemukan: ${found.getAddressLine(0) ?: searchQuery}"
                                    } else searchStatus = "Lokasi tidak ditemukan"
                                } catch (e: Exception) { searchStatus = "Pencarian gagal: ${e.localizedMessage}" }
                            }
                        }) { Icon(Icons.Default.Search, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Cari") }
                        Text(searchStatus, color = Blue, style = MaterialTheme.typography.bodySmall)
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = searchedPlace != null && !routeLoading,
                            onClick = {
                                val destination = searchedPlace
                                if (destination != null) scope.launch { buildRoute(destination, searchQuery) }
                            }
                        ) {
                            if (routeLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            else Icon(Icons.Default.Directions, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (routeLoading) "Menghitung rute…" else "Buat rute ke sini")
                        }
                        if (routePoints.isNotEmpty()) {
                            Text(routeSummary, fontWeight = FontWeight.SemiBold, color = Navy)
                            Text(routeInstruction, color = Color.DarkGray, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(modifier = Modifier.weight(1f), onClick = {
                                    navigationActive = true
                                    selectedMenu = null
                                }) { Icon(Icons.Default.Navigation, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Mulai navigasi") }
                                OutlinedButton(onClick = {
                                    routePoints = emptyList()
                                    routeSummary = ""
                                    routeInstruction = ""
                                    navigationActive = false
                                }) { Text("Hapus rute") }
                            }
                        }
                    }
                    "Pengaturan" -> {
                        Text("Izin lokasi: ${if (permissionGranted) "Aktif" else "Belum diberikan"}", color = Navy)
                        Text("Nama lokasi: $myPlaceName", color = Color.Gray)
                        Text("Lokasi tersimpan: ${if (savedLocation.isBlank()) "Belum ada" else savedLocation}", color = Color.Gray)
                        OutlinedButton(onClick = { requestLocation() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.MyLocation, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Perbarui lokasi sekarang")
                        }
                        OutlinedButton(onClick = {
                            val point = myLocation
                            if (point != null) {
                                savedLocation = "${point.latitude},${point.longitude}"
                                prefs.edit().putString("saved_location", savedLocation).apply()
                                android.widget.Toast.makeText(context, "Lokasi berhasil disimpan", android.widget.Toast.LENGTH_SHORT).show()
                            } else android.widget.Toast.makeText(context, "Lokasi GPS belum tersedia", android.widget.Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.BookmarkAdd, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Simpan lokasi saat ini")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun MenuTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.height(76.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFF7FAFF),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2EAF5)),
        onClick = onClick
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, tint = Blue, modifier = Modifier.size(25.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, color = Navy, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
