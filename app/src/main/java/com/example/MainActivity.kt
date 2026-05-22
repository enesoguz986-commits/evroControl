package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.example.data.GestureMappingEntity
import com.example.service.FeedbackOverlayView
import com.example.service.GestureAccessibilityService
import com.example.service.HandGestureAnalyzer
import com.example.ui.GestureViewModel
import com.example.ui.theme.MyApplicationTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        cameraExecutor = Executors.newSingleThreadExecutor()

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    GestureDashboardScreen(
                        modifier = Modifier.padding(innerPadding),
                        cameraExecutor = cameraExecutor
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureDashboardScreen(
    modifier: Modifier = Modifier,
    cameraExecutor: ExecutorService,
    viewModel: GestureViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mappings by viewModel.gestureMappings.collectAsStateWithLifecycle()

    // Permission and Service states
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasOverlayPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        )
    }
    var isAccessibilityEnabled by remember {
        mutableStateOf(isAccessibilityServiceEnabled(context))
    }

    // Interactive Camera Testing State inside Main App
    var isTestingActive by remember { mutableStateOf(false) }
    var testStatusText by remember { mutableStateOf("Kamerayı başlatmak için dokunun") }
    var latestResultText by remember { mutableStateOf("") }
    var detectedGestureOverlayView by remember { mutableStateOf<FeedbackOverlayView?>(null) }

    // Dropdown Dialog state for selecting custom actions
    var mappingToEdit by remember { mutableStateOf<GestureMappingEntity?>(null) }

    // Action Translation Mappings (Turkish/English UI labels)
    val actionTranslation = mapOf(
        "BACK" to "Geri Git (Sistem Geri)",
        "HOME" to "Ana Ekran (Home Başlat)",
        "RECENTS" to "Son Uygulamalar",
        "NOTIFICATIONS" to "Bildirimleri Çek",
        "SCROLL_UP" to "Yukarı Kaydır (Aşağı Çek)",
        "SCROLL_DOWN" to "Aşağı Kaydır (Yukarı Çek)",
        "VOLUME_UP" to "Sesi Artır",
        "VOLUME_DOWN" to "Sesi Azalt",
        "NONE" to "Devre Dışı / Eylemsiz"
    )

    // Setup permission launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    // Refresh dynamic states on resume callback simulation
    LaunchedEffect(Unit) {
        while (true) {
            isAccessibilityEnabled = isAccessibilityServiceEnabled(context)
            hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
            delay(1000)
        }
    }

    // Clean Minimalism Color Palette Settings
    val colorBg = Color(0xFFF7F9FC)
    val colorTextPrimary = Color(0xFF0F172A) // Slate 900
    val colorTextSecondary = Color(0xFF475569) // Slate 600
    val colorTextMuted = Color(0xFF94A3B8) // Slate 400
    val colorAccent = Color(0xFF2563EB) // Blue 600
    val colorBorder = Color(0xFFE2E8F0) // Slate 200
    val colorCardBg = Color.White

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colorBg)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp)
        ) {
            // Header Bar in Clean MD3 Minimalism Layout
            item {
                val engineActive = isAccessibilityEnabled && hasCameraPermission && hasOverlayPermission

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Avatar Hand Emoji Icon Container
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colorAccent)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "👋",
                            fontSize = 20.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "GestureLink",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = colorTextPrimary
                        )
                        
                        Text(
                            text = if (engineActive) "ARKA PLAN AKTİF" else "KAYIT BEKLENİYOR",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (engineActive) colorAccent else colorTextMuted,
                            letterSpacing = 1.sp
                        )
                    }

                    // iOS style switch or simple status toggle representation
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (engineActive) colorAccent.copy(alpha = 0.1f) else colorTextMuted.copy(alpha = 0.1f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(if (engineActive) Color(0xFF2ECC71) else Color(0xFFE74C3C), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (engineActive) "Çalışıyor" else "Durdu",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (engineActive) colorAccent else colorTextSecondary
                        )
                    }
                }
            }

            // Real-time Calibration Preview Box (Clean Minimalism, Rounded, Aspect-Video styled)
            item {
                Card(
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = colorCardBg
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, colorBorder, RoundedCornerShape(28.dp)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colorAccent.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = null,
                                    tint = colorAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Kalibrasyon Testi",
                                    color = colorTextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = "Düşük gecikmeli yerel takip motoru",
                                    color = colorTextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            Button(
                                onClick = {
                                    if (!hasCameraPermission) {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    } else {
                                        isTestingActive = !isTestingActive
                                        testStatusText = if (isTestingActive) {
                                            "El tespiti başlatıldı..."
                                        } else {
                                            "Kamerayı başlatmak için dokunun"
                                        }
                                    }
                                },
                                modifier = Modifier.height(34.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isTestingActive) Color(0xFFE74C3C) else colorAccent
                                ),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text(
                                    text = if (isTestingActive) "Kapat" else "Aç",
                                    fontSize = 12.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Sliding transition exposing Camera Preview box with mock frame boundary
                        AnimatedVisibility(
                            visible = isTestingActive,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(modifier = Modifier.padding(top = 12.dp)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp)
                                        .clip(RoundedCornerShape(20.dp))
                                        .border(4.dp, Color.White, RoundedCornerShape(20.dp))
                                        .background(Color(0xFF0F172A)) // bg-slate-900 style
                                ) {
                                    // Live Camera feed with transparent HUD Overlay linked
                                    if (isTestingActive && hasCameraPermission) {
                                        AndroidCameraPreview(
                                            lifecycleOwner = lifecycleOwner,
                                            cameraExecutor = cameraExecutor,
                                            onOverlayRegistered = { overlay ->
                                                detectedGestureOverlayView = overlay
                                            },
                                            onGestureEvaluated = { gesture ->
                                                latestResultText = gesture
                                            }
                                        )
                                    }

                                    // Custom visual telemetry overlay sticker like in HTML design mockup
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(10.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color.Black.copy(alpha = 0.5f))
                                            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                            .padding(horizontal = 10.dp, vertical = 5.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .background(Color(0xFF2ECC71), CircleShape)
                                            )
                                            Text(
                                                text = "GECİKME: 8ms",
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Tespit: ",
                                        color = colorTextSecondary,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = if (latestResultText.isEmpty() || latestResultText == "NONE") "El Aranıyor..." else latestResultText,
                                        color = if (latestResultText.isEmpty() || latestResultText == "NONE") Color(0xFFF59E0B) else Color(0xFF10B981),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }

                        if (!isTestingActive) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Arka planda düşük CPU tüketimiyle çalışması için optimize edilmiştir. Test ederek hareketlerin akıcılığını hissedin.",
                                fontSize = 11.sp,
                                color = colorTextMuted,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }

            // System Permissions and Setup cards (Clean white minimalism cards)
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = colorCardBg
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, colorBorder, RoundedCornerShape(24.dp)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFFF1F5F9)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = Color(0xFF64748B),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Gerekli Kurulum Adımları",
                                color = colorTextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Step 1: Camera Access
                        PermissionRow(
                            stepNumber = 1,
                            title = "Kamera İzni",
                            subtitle = "Gerçek zamanlı parmak tespiti için.",
                            isGranted = hasCameraPermission,
                            accentColor = colorAccent,
                            onClick = {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        )

                        // Step 2: Draw Overlay
                        PermissionRow(
                            stepNumber = 2,
                            title = "Üstte Gösterim Penceresi",
                            subtitle = "Arka planda küçük kamera izleyicisi için.",
                            isGranted = hasOverlayPermission,
                            accentColor = colorAccent,
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                }
                            }
                        )

                        // Step 3: Accessibility
                        PermissionRow(
                            stepNumber = 3,
                            title = "Erişilebilirlik Hizmeti",
                            subtitle = "Cihaz hareketlerini tetiklemek içindir.",
                            isGranted = isAccessibilityEnabled,
                            accentColor = colorAccent,
                            onClick = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                            }
                        )
                        
                        Divider(modifier = Modifier.padding(vertical = 12.dp), color = colorBorder)
                        
                        // Service activation helper hint
                        Text(
                            text = "💡 Önemli: Erişilebilirlik menüsünde 'Gesture Controller' uygulamasını bulup aktifleştirin. Sol tarafta küçük kontrol baloncuğu belirecektir.",
                            fontSize = 11.sp,
                            color = colorAccent,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Custom Layout Mapping Grid Header
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colorAccent.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = colorAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Kontrolleri Özelleştir",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = colorTextPrimary
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    val activeCount = mappings.count { it.isEnabled }
                    Text(
                        text = "$activeCount Aktif",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorAccent
                    )
                }
            }

            if (mappings.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = colorAccent)
                    }
                }
            } else {
                items(mappings) { mapping ->
                    GestureMappingCard(
                        mapping = mapping,
                        translatedActionName = actionTranslation[mapping.mappedAction] ?: mapping.mappedAction,
                        onConfigClicked = {
                            mappingToEdit = mapping
                        },
                        onEnableChanged = { isEnabled ->
                            viewModel.updateMapping(mapping.copy(isEnabled = isEnabled))
                        },
                        colorCardBg = colorCardBg,
                        colorBorder = colorBorder,
                        colorTextPrimary = colorTextPrimary,
                        colorTextSecondary = colorTextSecondary,
                        colorTextMuted = colorTextMuted,
                        colorAccent = colorAccent
                    )
                }
            }
        }
    }

    // Action Selector Modal Dialog sheet (Clean Minimalist Dialog styling)
    mappingToEdit?.let { mapping ->
        AlertDialog(
            onDismissRequest = { mappingToEdit = null },
            title = {
                Text(
                    text = "${mapping.gestureNameTr} için Tetiklenecek Eylem",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorTextPrimary
                )
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(24.dp),
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val actionList = listOf(
                        "BACK", "HOME", "RECENTS", "NOTIFICATIONS", 
                        "SCROLL_UP", "SCROLL_DOWN", 
                        "VOLUME_UP", "VOLUME_DOWN", "NONE"
                    )
                    items(actionList) { act ->
                        val labelTr = actionTranslation[act] ?: act
                        val isSelected = mapping.mappedAction == act

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) colorAccent.copy(alpha = 0.08f) else Color.Transparent)
                                .clickable {
                                    viewModel.updateMapping(mapping.copy(mappedAction = act))
                                    mappingToEdit = null
                                }
                                .padding(12.dp)
                                .testTag("select_action_${act.lowercase()}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = labelTr,
                                color = if (isSelected) colorAccent else colorTextPrimary,
                                modifier = Modifier.weight(1f),
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = colorAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { mappingToEdit = null }) {
                    Text("İptal", color = colorAccent, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

// Android CameraX integration for testing within MainActivity on lifecycle scopes

@Composable
fun AndroidCameraPreview(
    lifecycleOwner: LifecycleOwner,
    cameraExecutor: ExecutorService,
    onOverlayRegistered: (FeedbackOverlayView) -> Unit,
    onGestureEvaluated: (String) -> Unit
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }
    val overlayView = remember { FeedbackOverlayView(context) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        AndroidView(
            factory = {
                overlayView.also { onOverlayRegistered(it) }
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    LaunchedEffect(Unit) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        cameraProvider.unbindAll()

        val preview = Preview.Builder().build().apply {
            surfaceProvider = previewView.surfaceProvider
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, HandGestureAnalyzer(
                    onGestureDetected = { gesture ->
                        onGestureEvaluated(gesture)
                    },
                    onAnalysisUpdate = { res ->
                        overlayView.updateResult(res)
                    }
                ))
            }

        try {
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@Composable
fun PermissionRow(
    stepNumber: Int,
    title: String,
    subtitle: String,
    isGranted: Boolean,
    accentColor: Color = Color(0xFF2563EB),
    onClick: () -> Unit
) {
    val titleColor = Color(0xFF0F172A)
    val subtitleColor = Color(0xFF475569)
    val activeSuccess = Color(0xFF10B981) // Green 500

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (!isGranted) onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Step number node
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(
                    if (isGranted) activeSuccess.copy(alpha = 0.12f) else accentColor.copy(alpha = 0.12f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stepNumber.toString(),
                color = if (isGranted) activeSuccess else accentColor,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = titleColor
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = subtitleColor,
                lineHeight = 15.sp
            )
        }

        Icon(
            imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.ChevronRight,
            contentDescription = null,
            tint = if (isGranted) activeSuccess else Color(0xFF94A3B8),
            modifier = Modifier
                .size(22.dp)
                .testTag("step_icon_$stepNumber")
        )
    }
}

@Composable
fun GestureMappingCard(
    mapping: GestureMappingEntity,
    translatedActionName: String,
    onConfigClicked: () -> Unit,
    onEnableChanged: (Boolean) -> Unit,
    colorCardBg: Color = Color.White,
    colorBorder: Color = Color(0xFFE2E8F0),
    colorTextPrimary: Color = Color(0xFF0F172A),
    colorTextSecondary: Color = Color(0xFF475569),
    colorTextMuted: Color = Color(0xFF94A3B8),
    colorAccent: Color = Color(0xFF2563EB)
) {
    val activeSuccess = Color(0xFF10B981)

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = colorCardBg
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (mapping.isEnabled) colorAccent.copy(alpha = 0.4f) else colorBorder,
                RoundedCornerShape(24.dp)
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Determine beautiful matching gesture representation icon and warm pastel background
                val (gestureIcon, iconTintColor, iconBgColor) = when (mapping.gestureId) {
                    "SWIPE_LEFT" -> Triple(Icons.Default.ArrowBack, Color(0xFF2563EB), Color(0xFFEFF6FF))
                    "SWIPE_RIGHT" -> Triple(Icons.Default.ArrowForward, Color(0xFF2563EB), Color(0xFFEFF6FF))
                    "SWIPE_UP" -> Triple(Icons.Default.ArrowUpward, Color(0xFF2563EB), Color(0xFFEFF6FF))
                    "SWIPE_DOWN" -> Triple(Icons.Default.ArrowDownward, Color(0xFF2563EB), Color(0xFFEFF6FF))
                    "FIST" -> Triple(Icons.Default.Gesture, Color(0xFFEA580C), Color(0xFFFFF7ED))
                    else -> Triple(Icons.Default.WavingHand, Color(0xFF7C3AED), Color(0xFFFAF5FF))
                }

                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconBgColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = gestureIcon,
                        contentDescription = null,
                        tint = iconTintColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mapping.gestureNameTr,
                        fontWeight = FontWeight.Bold,
                        color = colorTextPrimary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = mapping.gestureDescription,
                        fontSize = 11.sp,
                        color = colorTextSecondary,
                        lineHeight = 15.sp
                    )
                }

                // Clean minimalist switch widget matching system accents
                Switch(
                    checked = mapping.isEnabled,
                    onCheckedChange = onEnableChanged,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = activeSuccess,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = colorTextMuted.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.testTag("switch_${mapping.gestureId.lowercase()}")
                )
            }

            Divider(modifier = Modifier.padding(vertical = 12.dp), color = colorBorder)

            // Dynamic Action button with responsive Slate aesthetics
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFF8FAFC)) // bg-slate-50 style container
                    .border(1.dp, Color(0xFFF1F5F9), RoundedCornerShape(14.dp))
                    .clickable { onConfigClicked() }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("config_btn_${mapping.gestureId.lowercase()}")
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = colorAccent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Aksiyon:",
                    color = colorTextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = translatedActionName,
                    color = colorAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = colorTextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// Global Accessibility check script
private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val service = context.packageName + "/" + GestureAccessibilityService::class.java.canonicalName
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.contains(service)
}

// Help simulate basic delay logic
private suspend fun delay(timeMs: Long) {
    kotlinx.coroutines.delay(timeMs)
}
