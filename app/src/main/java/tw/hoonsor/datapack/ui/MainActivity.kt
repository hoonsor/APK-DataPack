package tw.hoonsor.datapack.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tw.hoonsor.datapack.BuildConfig
import tw.hoonsor.datapack.Graph
import tw.hoonsor.datapack.capture.Media
import tw.hoonsor.datapack.capture.Pipeline
import tw.hoonsor.datapack.log.AppLog
import tw.hoonsor.datapack.ui.theme.C
import tw.hoonsor.datapack.ui.theme.DataPackTheme
import java.io.File

/** 導覽目的地（分頁之上的堆疊） */
sealed interface Dest {
    data class Pack(val key: String) : Dest
    data class Item(val id: String) : Dest
    data class Source(val itemId: String?, val sourceId: String?, val chunkId: String?) : Dest
    data class PackEdit(val key: String?) : Dest
    data object Jobs : Dest
    data object Backup : Dest
    data object Log : Dest
    data object Health : Dest
}

data class Undo(val label: String, val action: suspend () -> Unit)

class MainActivity : ComponentActivity() {
    private val stack = mutableStateListOf<Dest>()
    private var tab by mutableStateOf(Tab.Packs)
    private var scanRequested by mutableStateOf(false)
    private val snack = mutableStateOf<Undo?>(null)
    private val message = mutableStateOf<String?>(null)

    private lateinit var scanner: GmsDocumentScanner

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val pages = GmsDocumentScanningResult.fromActivityResultIntent(r.data)?.pages.orEmpty().map { it.imageUri }
        receive(pages, "scan")
    }

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> receive(uris, "pick") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 永遠深色介面：狀態列用淺色字
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        scanner = GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true).setPageLimit(50)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build(),
        )
        handle(intent)
        setContent { DataPackTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent) {
        if (i.getBooleanExtra(EXTRA_SCAN, false)) scanRequested = true
        if (i.getBooleanExtra(EXTRA_BACKUP, false)) stack += Dest.Backup
        val item = i.getStringExtra(EXTRA_ITEM)
        val source = i.getStringExtra(EXTRA_SOURCE)
        if (item != null || source != null) stack += Dest.Source(item, source, i.getStringExtra(EXTRA_CHUNK))
        val uris: List<Uri> = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            receive(uris, "share")
            i.action = Intent.ACTION_MAIN
        }
    }

    /** 掃描、挑檔、分享三個入口匯流到這裡；多選分享每個檔案各算一筆，掃描多頁才算一份 */
    private fun receive(uris: List<Uri>, origin: String) {
        if (uris.isEmpty()) return
        Graph.scope.launch {
            runCatching {
                val pdfs = uris.filter { contentResolver.getType(it) == "application/pdf" || it.toString().endsWith(".pdf", true) }
                val images = uris - pdfs.toSet()
                pdfs.forEach { Pipeline.enqueue(this@MainActivity, listOf(Media.copyToInbox(this@MainActivity, it, "pdf")), origin) }
                val files: List<File> = images.map { Media.copyToInbox(this@MainActivity, it, "jpg") }
                if (origin == "share") files.forEach { Pipeline.enqueue(this@MainActivity, listOf(it), origin) }
                else if (files.isNotEmpty()) Pipeline.enqueue(this@MainActivity, files, origin)
                message.value = "收到 ${uris.size} 個檔案，處理中"
            }.onFailure {
                AppLog.error("capture", "收件失敗", it)
                message.value = "收件失敗：${it.message}"
            }
        }
    }

    private fun startScan() {
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { scanLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener {
                AppLog.warn("capture", "文件掃描器不可用：${it.message}")
                message.value = "文件掃描器不能用，改從相簿選（設定 › 健康檢查可看原因）"
                pickLauncher.launch(arrayOf("image/*", "application/pdf"))
            }
    }

    @Composable
    private fun Root() {
        var signedIn by remember { mutableStateOf(Graph.api.signedIn || previewMode) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) { if (previewMode) Preview.seed() }
        LaunchedEffect(scanRequested) {
            if (scanRequested) {
                scanRequested = false
                startScan()
            }
        }
        val undo by snack
        LaunchedEffect(undo) {
            if (undo != null) {
                delay(10_000)
                snack.value = null
            }
        }
        val msg by message
        LaunchedEffect(msg) {
            if (msg != null) {
                delay(3_500)
                message.value = null
            }
        }
        val review by Graph.db.dao().reviewFlow().collectAsState(emptyList())

        BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
        Box(Modifier.fillMaxSize().background(C.bg).safeDrawingPadding().imePadding()) {
            if (!signedIn) {
                LoginScreen { signedIn = true; Graph.scope.launch { Graph.sync.sync() } }
                return@Box
            }
            val nav = Nav(
                push = { stack += it },
                pop = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) },
                scan = ::startScan,
                pick = { pickLauncher.launch(arrayOf("image/*", "application/pdf")) },
                undo = { snack.value = it },
                toast = { message.value = it },
                signedOut = { signedIn = false; stack.clear() },
            )
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    when (val top = stack.lastOrNull()) {
                        null -> when (tab) {
                            Tab.Packs -> PacksScreen(nav)
                            Tab.Search -> SearchScreen(nav)
                            Tab.Review -> ReviewScreen(nav)
                            Tab.Settings -> SettingsScreen(nav)
                        }
                        is Dest.Pack -> PackItemsScreen(top.key, nav)
                        is Dest.Item -> ItemScreen(top.id, nav)
                        is Dest.Source -> SourceScreen(top, nav)
                        is Dest.PackEdit -> PackEditScreen(top.key, nav)
                        Dest.Jobs -> JobsScreen(nav)
                        Dest.Backup -> BackupScreen(nav)
                        Dest.Log -> LogScreen(nav)
                        Dest.Health -> HealthScreen(nav)
                    }
                }
                if (stack.isEmpty()) Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp)) { BottomNav(tab, review.size) { tab = it } }
            }
            val u = undo
            if (u != null) {
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth().background(C.surfaceHi, R20).padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(u.label, color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    OutlineButton("復原", color = C.accentSoft, border = C.accent) {
                        scope.launch { u.action() }
                        snack.value = null
                    }
                }
            } else if (msg != null) {
                Text(msg.orEmpty(), color = C.text, fontSize = 15.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).background(C.surfaceHi, R20).padding(horizontal = 16.dp, vertical = 14.dp))
            }
        }
    }

    private val previewMode get() = BuildConfig.FLAVOR == "lab" && intent.getBooleanExtra("preview", false)

    companion object {
        const val EXTRA_SCAN = "scan"
        const val EXTRA_BACKUP = "backup"
        const val EXTRA_ITEM = "item"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_CHUNK = "chunk"
    }
}

class Nav(
    val push: (Dest) -> Unit,
    val pop: () -> Unit,
    val scan: () -> Unit,
    val pick: () -> Unit,
    val undo: (Undo) -> Unit,
    val toast: (String) -> Unit,
    val signedOut: () -> Unit,
)
