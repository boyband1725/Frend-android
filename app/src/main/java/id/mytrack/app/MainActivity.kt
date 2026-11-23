package id.mytrack.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
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

private val Navy = Color(0xFF123B70)
private val Blue = Color(0xFF2878E5)
private val Pale = Color(0xFFF3F7FC)

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
    var partnerLocation by remember { mutableStateOf<LatLng?>(null) }
    var pairCode by remember { mutableStateOf(prefs.getString("pair_code", "") ?: "") }
    var codeInput by remember { mutableStateOf(pairCode) }
    var sharing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Lokasi belum diaktifkan") }
    var permissionGranted by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    ) }
    var lastPartnerUpdate by remember { mutableStateOf("Belum ada lokasi pasangan") }
    val client = remember { OkHttpClient() }
    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(-6.2, 106.816666), 12f)
    }

    fun requestLocation() {
        if (!permissionGranted) return
        try {
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        val point = LatLng(loc.latitude, loc.longitude)
                        myLocation = point
                        scope.launch { camera.animate(CameraUpdateFactory.newLatLngZoom(point, 16f)) }
                    } else status = "GPS belum mendapatkan lokasi. Coba di luar ruangan."
                }
                .addOnFailureListener { status = "Gagal mengambil lokasi: ${it.localizedMessage}" }
        } catch (_: SecurityException) { status = "Izin lokasi belum diberikan" }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (permissionGranted) requestLocation() else status = "Izin lokasi diperlukan untuk berbagi lokasi"
    }

    LaunchedEffect(permissionGranted) { if (permissionGranted) requestLocation() }

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

    Scaffold(containerColor = Pale, topBar = {
        TopAppBar(
            title = { Column { Text("MYtrack", fontWeight = FontWeight.Bold, color = Navy); Text("Berbagi lokasi bersama", style = MaterialTheme.typography.labelSmall, color = Color.Gray) } },
            actions = { IconButton(onClick = { requestLocation() }) { Icon(Icons.Default.MyLocation, contentDescription = "Lokasi saya", tint = Blue) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = camera,
                    properties = MapProperties(isMyLocationEnabled = permissionGranted),
                    uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = false)
                ) {
                    myLocation?.let { Marker(state = MarkerState(position = it), title = "Lokasi saya", snippet = "Perangkat ini") }
                    partnerLocation?.let { Marker(state = MarkerState(position = it), title = "Pasangan", snippet = lastPartnerUpdate) }
                }
                if (myLocation == null) {
                    Card(Modifier.align(Alignment.Center).padding(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Text("Aktifkan izin lokasi untuk melihat posisi kamu", Modifier.padding(16.dp), color = Navy)
                    }
                }
                Surface(Modifier.align(Alignment.TopStart).padding(12.dp), shape = RoundedCornerShape(24.dp), color = Color.White, shadowElevation = 4.dp) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(if (sharing) Color(0xFF22A06B) else Color.Gray, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(if (sharing) "Berbagi aktif" else "Berbagi dijeda", style = MaterialTheme.typography.labelMedium, color = Navy)
                    }
                }
            }
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Berbagi lokasi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy)
                    OutlinedTextField(
                        value = codeInput, onValueChange = { codeInput = it.uppercase().take(12) },
                        modifier = Modifier.fillMaxWidth(), label = { Text("Kode pasangan (minimal 6 karakter)") },
                        singleLine = true, leadingIcon = { Icon(Icons.Default.Group, contentDescription = null) }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (codeInput.trim().length >= 6) {
                                    pairCode = codeInput.trim().uppercase()
                                    prefs.edit().putString("pair_code", pairCode).apply()
                                    status = "Kode pasangan tersimpan"
                                } else status = "Kode minimal 6 karakter"
                            }
                        ) { Text("Simpan kode") }
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (!permissionGranted) permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                else if (pairCode.length < 6) status = "Simpan kode pasangan terlebih dahulu"
                                else { sharing = !sharing; status = if (sharing) "Mengaktifkan berbagi..." else "Berbagi lokasi dihentikan" }
                            }
                        ) {
                            Icon(if (sharing) Icons.Default.Pause else Icons.Default.ShareLocation, contentDescription = null)
                            Spacer(Modifier.width(6.dp)); Text(if (sharing) "Hentikan" else "Mulai berbagi")
                        }
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = Blue)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Favorite, contentDescription = null, tint = Color(0xFFE45D78), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(lastPartnerUpdate, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                    }
                    Text("Lokasi hanya dibagikan saat tombol Mulai berbagi aktif dan aplikasi sedang terbuka.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
        }
    }
}

