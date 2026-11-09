package com.example.arplace

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingFailureReason
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberView

/** Элемент каталога: файл модели, название, иконка и желаемый размер (в метрах) наибольшей стороны. */
data class CatalogItem(val file: String, val name: String, val emoji: String, val size: Float)

val CATALOG = listOf(
    CatalogItem("table.glb", "Стол", "🪑", 1.2f),
    CatalogItem("chair.glb", "Стул", "💺", 0.9f),
    CatalogItem("sofa.glb", "Диван", "🛋️", 1.8f),
    CatalogItem("bed.glb", "Кровать", "🛏️", 2.0f),
    CatalogItem("shelf.glb", "Стеллаж", "📚", 1.8f),
    CatalogItem("lamp.glb", "Торшер", "💡", 1.5f),
    CatalogItem("plant.glb", "Растение", "🪴", 0.9f),
    CatalogItem("wardrobe.glb", "Шкаф", "🚪", 2.0f),
    CatalogItem("generator.glb", "Генератор", "⚙️", 0.8f),
    CatalogItem("model2.glb", "Модель 2", "📦", 1.0f),
)

class MainActivity : ComponentActivity() {
    private var granted by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
        setContent {
            MaterialTheme {
                if (granted) ArScreen(onScreenshot = ::takeScreenshot)
                else Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Нужен доступ к камере", textAlign = TextAlign.Center)
                }
            }
        }
    }

    private fun takeScreenshot() {
        val view = window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(window, bmp, { result ->
            if (result == PixelCopy.SUCCESS) save(bmp)
            else Toast.makeText(this, "Не удалось сделать снимок", Toast.LENGTH_SHORT).show()
        }, Handler(Looper.getMainLooper()))
    }

    private fun save(bmp: Bitmap) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ARPlace_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ARPlace")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(this, "Ошибка сохранения", Toast.LENGTH_SHORT).show(); return
        }
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Toast.makeText(this, "Сохранено в Pictures/ARPlace", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun ArScreen(onScreenshot: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val cameraNode = rememberARCameraNode(engine)
    val childNodes = rememberNodes()
    val view = rememberView(engine)
    val collisionSystem = rememberCollisionSystem(view)
    val context = LocalContext.current

    val frameHolder = remember { arrayOfNulls<Frame>(1) }
    var tracking by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var planeFound by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(CATALOG[0]) }
    var selected by remember { mutableStateOf<ModelNode?>(null) }

    fun place(x: Float, y: Float) {
        val frame = frameHolder[0] ?: return
        val hit = frame.hitTest(x, y).firstOrNull {
            val t = it.trackable
            t is Plane && t.type == Plane.Type.HORIZONTAL_UPWARD_FACING && t.isPoseInPolygon(it.hitPose)
        }
        if (hit == null) {
            Toast.makeText(context, "Наведите камеру на пол или стол", Toast.LENGTH_SHORT).show()
            return
        }
        val anchorNode = AnchorNode(engine = engine, anchor = hit.createAnchor())
        val model = ModelNode(
            modelInstance = modelLoader.createModelInstance("models/${current.file}"),
            scaleToUnits = current.size
        ).apply { isEditable = true }   // жесты: перенос, поворот двумя пальцами, щипок-масштаб
        anchorNode.addChildNode(model)
        childNodes += anchorNode
        selected = model
    }

    Box(Modifier.fillMaxSize()) {
        ARScene(
            modifier = Modifier.fillMaxSize(),
            childNodes = childNodes,
            engine = engine,
            view = view,
            modelLoader = modelLoader,
            collisionSystem = collisionSystem,
            cameraNode = cameraNode,
            planeRenderer = true,
            sessionConfiguration = { _, config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            },
            onTrackingFailureChanged = { tracking = it },
            onSessionUpdated = { session, frame ->
                frameHolder[0] = frame
                if (!planeFound && session.getAllTrackables(Plane::class.java)
                        .any { it.trackingState == com.google.ar.core.TrackingState.TRACKING }
                ) planeFound = true
            },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { event, node ->
                    if (node is ModelNode) selected = node
                    else if (node == null) place(event.x, event.y)
                }
            )
        )

        // Подсказка сверху
        val hint = when {
            tracking != null -> "Медленно двигайте телефон…"
            !planeFound -> "Ищем поверхность: водите камерой по полу"
            childNodes.isEmpty() -> "Выберите предмет и коснитесь поверхности"
            else -> "Объектов: ${childNodes.size}. Коснитесь предмета, чтобы выбрать"
        }
        Text(
            hint, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(12.dp)
                .background(Color(0x99000000), RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )

        // Кнопки управления выбранным объектом
        Column(
            Modifier.align(Alignment.CenterEnd).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ToolButton(Icons.Default.Add, "Крупнее") {
                selected?.let { val s = it.scale.x * 1.15f; it.scale = Float3(s, s, s) }
            }
            ToolButton(Icons.Default.Remove, "Мельче") {
                selected?.let { val s = it.scale.x / 1.15f; it.scale = Float3(s, s, s) }
            }
            ToolButton(Icons.Default.RotateRight, "Повернуть") {
                selected?.let { val r = it.rotation; it.rotation = Float3(r.x, r.y + 20f, r.z) }
            }
            ToolButton(Icons.Default.Delete, "Удалить") {
                selected?.let { m ->
                    m.parent?.let { p -> childNodes.remove(p); p.destroy() }
                    selected = null
                }
            }
            ToolButton(Icons.Default.CameraAlt, "Снимок") { onScreenshot() }
        }

        // Каталог внизу
        LazyRow(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .background(Color(0xCC000000))
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(CATALOG) { item ->
                val active = item == current
                Column(
                    Modifier
                        .width(76.dp)
                        .background(Color(0xFF2B2B2B), RoundedCornerShape(12.dp))
                        .border(2.dp, if (active) Color(0xFF4FC3F7) else Color.Transparent, RoundedCornerShape(12.dp))
                        .clickable { current = item }
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(item.emoji, fontSize = 28.sp)
                    Text(item.name, color = Color.White, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xCC000000))
    ) { Icon(icon, contentDescription = desc, tint = Color.White) }
}
