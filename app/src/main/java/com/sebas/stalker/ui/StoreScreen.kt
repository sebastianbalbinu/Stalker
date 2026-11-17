package com.sebas.stalker.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sebas.stalker.DownloadInfo
import com.sebas.stalker.Downloads
import com.sebas.stalker.Engine
import com.sebas.stalker.R
import kotlinx.coroutines.delay
import java.net.URLEncoder

/** Navegador de la tienda. Vive mientras la app está abierta, así no pierdes la página al cambiar de pestaña. */
class BrowserState(context: Context) {
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var loading by mutableStateOf(false)
    var progress by mutableStateOf(0f)
    var canBack by mutableStateOf(false)
    var home by mutableStateOf(true)

    @SuppressLint("SetJavaScriptEnabled")
    val web: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "http" || u.scheme == "https") return false
                // Enlaces a otras apps (intent:, market:, mailto:)
                return try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (e: Exception) {
                    true
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                this@BrowserState.loading = true
                this@BrowserState.url = url
                this@BrowserState.canBack = view.canGoBack()
            }

            override fun onPageFinished(view: WebView, url: String) {
                this@BrowserState.loading = false
                this@BrowserState.url = url
                this@BrowserState.title = view.title ?: ""
                this@BrowserState.canBack = view.canGoBack()
                Engine.setStoreUrl(url)
            }
        }
        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                this@BrowserState.progress = newProgress / 100f
            }
        }
        setDownloadListener { dlUrl, userAgent, contentDisposition, mimetype, _ ->
            val name = Downloads.enqueue(context, dlUrl, userAgent, contentDisposition, mimetype)
            val msg = when {
                name == null -> "No se pudo empezar la descarga"
                Downloads.isZip(name) -> "Descargando $name · se descomprime solo al terminar"
                Downloads.isAudio(name) -> "Descargando $name a Música/Stalker"
                else -> "Descargando $name a Descargas"
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    fun open(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val target = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains('.') && !t.contains(' ') -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + URLEncoder.encode(t, "UTF-8")
        }
        home = false
        web.loadUrl(target)
    }

    fun back() {
        if (web.canGoBack()) web.goBack() else home = true
    }
}

private data class Shop(val name: String, val blurb: String, val url: String, val tag: String)

private val shops = listOf(
    Shop("Bandcamp", "Compra directo a los artistas. Descarga en FLAC; mucha música es de \"paga lo que quieras\".", "https://bandcamp.com", "FLAC · Artistas"),
    Shop("Qobuz", "Tienda de descargas Hi-Res, hasta 24 bit / 192 kHz.", "https://www.qobuz.com/shop", "Hi-Res"),
    Shop("Live Music Archive", "Miles de conciertos en FLAC, gratis y legales, de bandas que permiten grabarlos.", "https://archive.org/details/etree", "Gratis"),
)

@Composable
fun StoreScreen(browser: BrowserState, bottomPadding: androidx.compose.ui.unit.Dp) {
    var showDownloads by remember { mutableStateOf(false) }
    val ids by Engine.downloads.collectAsState()
    val active = ids.size

    BackHandler(enabled = !browser.home) { browser.back() }

    if (browser.home) {
        StoreHome(browser, bottomPadding, active) { showDownloads = true }
    } else {
        Column(Modifier.fillMaxSize().background(C.Marfil).statusBarsPadding().navigationBarsPadding().padding(bottom = bottomPadding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SoftButton(Ico.Back, "Atrás", size = 40.dp) { browser.back() }
                Spacer(Modifier.width(8.dp))
                AddressBar(browser, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                SoftButton(if (browser.loading) Ico.Close else Ico.Refresh, if (browser.loading) "Detener" else "Recargar", size = 40.dp) {
                    if (browser.loading) browser.web.stopLoading() else browser.web.reload()
                }
                Spacer(Modifier.width(8.dp))
                DownloadsButton(active) { showDownloads = true }
            }
            if (browser.loading) {
                LinearProgressIndicator(
                    progress = { browser.progress },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = C.Rojo,
                    trackColor = C.Arena,
                )
            } else {
                Spacer(Modifier.height(2.dp))
            }
            AndroidView(
                factory = {
                    (browser.web.parent as? ViewGroup)?.removeView(browser.web)
                    browser.web
                },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
    if (showDownloads) DownloadsSheet { showDownloads = false }
}

@Composable
private fun AddressBar(browser: BrowserState, modifier: Modifier) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    if (editing) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            modifier = modifier,
            textStyle = Cuerpo.copy(fontSize = 14.sp),
            shape = RoundedCornerShape(20.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                browser.open(text)
                editing = false
                focus.clearFocus()
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = C.Rojo, unfocusedBorderColor = C.Linea, cursorColor = C.Rojo,
                focusedContainerColor = C.Tarjeta, unfocusedContainerColor = C.Tarjeta,
            ),
        )
    } else {
        val host = runCatching { Uri.parse(browser.url).host?.removePrefix("www.") }.getOrNull() ?: ""
        Column(
            modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(C.Tarjeta)
                .clickable { text = browser.url; editing = true }
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(browser.title.ifBlank { host }, style = Cuerpo.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(host, style = Secundario.copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DownloadsButton(active: Int, onClick: () -> Unit) {
    Box {
        SoftButton(Ico.Download, "Descargas", size = 40.dp, onClick = onClick)
        if (active > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(C.Rojo),
                contentAlignment = Alignment.Center
            ) { Text("$active", style = Cuerpo.copy(fontSize = 10.sp, color = C.SobreRojo, fontWeight = FontWeight.SemiBold)) }
        }
    }
}

@Composable
private fun StoreHome(browser: BrowserState, bottomPadding: androidx.compose.ui.unit.Dp, active: Int, onDownloads: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val last by Engine.lastStoreUrl.collectAsState()
    val focus = LocalFocusManager.current
    CurtainSheet {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = bottomPadding + 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tienda", style = Titulo, modifier = Modifier.weight(1f))
                DownloadsButton(active, onDownloads)
            }
            Spacer(Modifier.height(6.dp))
            Text("Busca y descarga música mientras sigue sonando la tuya. Lo que bajes aparece solo en tu biblioteca.", style = Secundario)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Escribe una dirección o busca", style = Cuerpo.copy(color = C.Piedra)) },
                leadingIcon = { Icon(Ico.Search, null, tint = C.Tierra, modifier = Modifier.size(20.dp)) },
                textStyle = Cuerpo,
                shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { browser.open(query); focus.clearFocus() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = C.Rojo, unfocusedBorderColor = C.Linea, cursorColor = C.Rojo,
                    focusedContainerColor = C.Tarjeta, unfocusedContainerColor = C.Tarjeta,
                ),
            )
            val lastUrl = last
            if (lastUrl != null && browser.url.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Arena)
                        .clickable { browser.home = false }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Ico.Refresh, null, tint = C.Carbon, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("Seguir donde ibas", style = Cuerpo.copy(fontWeight = FontWeight.Medium))
                        Text(browser.title.ifBlank { browser.url }, style = Secundario.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Ico.Right, null, tint = C.Tierra, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("TIENDAS", style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(10.dp))
            shops.forEachIndexed { i, shop ->
                ShopCard(shop, i) { browser.open(shop.url) }
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(8.dp))
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Cómo funciona", style = Cuerpo.copy(fontWeight = FontWeight.SemiBold))
                    Text("• Las canciones se guardan en Música/Stalker.", style = Secundario)
                    Text("• Los álbumes que vienen en .zip se descomprimen solos.", style = Secundario)
                    Text("• Las descargas siguen aunque cambies de pantalla o cierres la app.", style = Secundario)
                    Text("• En Bandcamp, al comprar elige el formato FLAC antes de descargar.", style = Secundario)
                }
            }
        }
    }
}

@Composable
private fun ShopCard(shop: Shop, i: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Tarjeta).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp))) {
            FallbackArt(i % 3, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(shop.name, style = Cuerpo.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(C.Rojo).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(shop.tag, style = Cuerpo.copy(fontSize = 10.sp, color = C.SobreRojo, fontWeight = FontWeight.Medium))
                }
            }
            Text(shop.blurb, style = Secundario.copy(fontSize = 12.sp, lineHeight = 16.sp))
        }
        Icon(Ico.Right, null, tint = C.Tierra, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun DownloadsSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val extracting by Engine.extracting.collectAsState()
    val list by produceState(initialValue = Downloads.list(ctx)) {
        while (true) {
            value = Downloads.list(ctx)
            delay(1000)
        }
    }
    StalkerSheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Descargas", style = Titulo.copy(fontSize = 24.sp), modifier = Modifier.weight(1f))
            if (list.any { it.status == DownloadManager.STATUS_SUCCESSFUL || it.status == DownloadManager.STATUS_FAILED }) {
                Pill("Limpiar", selected = false) { Downloads.clearFinished(ctx) }
            }
        }
        Text("Se guardan en Música/Stalker y aparecen solas en la biblioteca", style = Secundario)
        Spacer(Modifier.height(12.dp))
        if (list.isEmpty()) {
            Text("No hay descargas todavía.", style = Cuerpo.copy(color = C.Tierra), modifier = Modifier.padding(vertical = 24.dp))
        } else {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(list, key = { it.id }) { d ->
                    DownloadRow(d, d.id in extracting) { Downloads.cancel(ctx, d.id) }
                    HorizontalDivider(color = C.Arena, thickness = 1.dp)
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(d: DownloadInfo, unzipping: Boolean, onCancel: () -> Unit) {
    val mb = { b: Long -> "%.1f MB".format(b / 1_048_576.0) }
    val status = when {
        unzipping -> "Descomprimiendo el álbum…"
        d.status == DownloadManager.STATUS_RUNNING -> "${(d.progress * 100).toInt()}% · ${mb(d.done)} de ${mb(d.total)}"
        d.status == DownloadManager.STATUS_PENDING -> "En espera"
        d.status == DownloadManager.STATUS_PAUSED -> "En pausa · esperando conexión"
        d.status == DownloadManager.STATUS_SUCCESSFUL -> "Listo · ya está en tu biblioteca"
        d.status == DownloadManager.STATUS_FAILED -> "Falló"
        else -> ""
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(d.title, style = Cuerpo.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (d.status == DownloadManager.STATUS_RUNNING || unzipping) {
                LinearProgressIndicator(
                    progress = { if (unzipping) 1f else d.progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = C.Rojo,
                    trackColor = C.Arena,
                )
            }
            Text(status, style = Secundario.copy(fontSize = 12.sp, color = if (d.status == DownloadManager.STATUS_FAILED) C.Rojo else C.Tierra))
        }
        if (d.status != DownloadManager.STATUS_SUCCESSFUL && !unzipping) {
            Box(
                Modifier.padding(start = 8.dp).size(36.dp).clip(CircleShape).clickable(onClick = onCancel),
                contentAlignment = Alignment.Center
            ) { Icon(Ico.Close, "Cancelar", tint = C.Tierra, modifier = Modifier.size(16.dp)) }
        }
    }
}

