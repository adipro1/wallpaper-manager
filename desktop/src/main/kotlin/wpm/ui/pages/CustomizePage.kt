package wpm.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import wpm.AppState
import wpm.data.MediaItem
import wpm.media.SHADER_EXTENSIONS
import wpm.ui.Theme
import wpm.ui.components.Card
import wpm.ui.components.ConfirmDialog
import wpm.ui.components.MutedText
import wpm.ui.components.PageScaffold
import wpm.ui.components.SectionTitle
import wpm.ui.components.WebPreview
import java.awt.FileDialog
import java.awt.Frame

/** Create and edit shader wallpapers (Shadertoy-style GLSL) with a live preview. */
@Composable
fun CustomizePage(app: AppState) {
    var shaders by remember { mutableStateOf(emptyList<MediaItem>()) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var newMenu by remember { mutableStateOf(false) }
    LaunchedEffect(app.libraryVersion) {
        shaders = withContext(Dispatchers.IO) { app.db.listMedia(mediaType = "shader", sort = "name") }
        if (selectedId == null || shaders.none { it.id == selectedId }) selectedId = shaders.firstOrNull()?.id
    }

    fun create(name: String, code: String) = app.runTask("Created “$name”", "Couldn't create the shader") {
        selectedId = app.shaders.create(name, code, interactive = "iMouse" in code, audio = "iChannel0" in code)
        app.libraryChanged()
    }

    PageScaffold("Customize", "Make your own animated wallpapers with shaders (Shadertoy-style GLSL).", scroll = false, actions = {
        OutlinedButton(onClick = {
            val dialog = FileDialog(app.window as? Frame, "Import a shader", FileDialog.LOAD).apply {
                file = SHADER_EXTENSIONS.joinToString(";") { "*.$it" }
            }
            dialog.isVisible = true
            dialog.files.firstOrNull()?.let { file ->
                app.runTask("Shader imported", "Couldn't import the shader") {
                    selectedId = app.shaders.import(file.toPath())
                    app.libraryChanged()
                }
            }
        }) { Icon(Icons.Outlined.UploadFile, null); Text("  Import .glsl") }
        Box {
            Button(onClick = { newMenu = true }) { Icon(Icons.Outlined.Add, null); Text("  New shader") }
            DropdownMenu(newMenu, { newMenu = false }) {
                DropdownMenuItem(text = { Text("Blank") }, onClick = { newMenu = false; create("My shader", BLANK_SHADER) })
                HorizontalDivider()
                app.shaders.samples().forEach { sample ->
                    DropdownMenuItem(text = { Text("Sample: ${sample.name}") }, onClick = { newMenu = false; create(sample.name, sample.code) })
                }
            }
        }
    }) {
        if (shaders.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                SectionTitle("No shaders yet")
                Spacer(Modifier.height(6.dp))
                MutedText("Shaders are small programs that draw animated wallpapers on your graphics card. " +
                    "Start from a sample with “New shader”, or import a .glsl file (for example from Shadertoy).")
            }
            return@PageScaffold
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(Modifier.width(220.dp).fillMaxHeight(), padding = PaddingValues(8.dp)) {
                LazyColumn {
                    items(shaders, key = { it.id }) { s ->
                        Text(
                            s.filename, color = Theme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .background(if (s.id == selectedId) Theme.colors.navSelected else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { selectedId = s.id }.padding(12.dp),
                        )
                    }
                }
            }
            shaders.firstOrNull { it.id == selectedId }?.let { ShaderEditor(app, it, Modifier.weight(1f).fillMaxHeight()) }
        }
    }
}

@Composable
private fun ShaderEditor(app: AppState, item: MediaItem, modifier: Modifier) {
    var code by remember(item.id) { mutableStateOf("") }
    var saved by remember(item.id) { mutableStateOf("") }
    var name by remember(item.id) { mutableStateOf(item.filename) }
    var interactive by remember(item.id) { mutableStateOf(item.interactive) }
    var audio by remember(item.id) { mutableStateOf(item.audio) }
    var previewUrl by remember(item.id) { mutableStateOf<String?>(null) }
    var status by remember(item.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(item.id) {
        code = withContext(Dispatchers.IO) { app.shaders.code(item) }
        saved = code
    }
    // Recompile the preview shortly after typing stops.
    LaunchedEffect(code) {
        if (code.isEmpty()) return@LaunchedEffect
        delay(700)
        previewUrl = withContext(Dispatchers.IO) { app.shaders.previewPage(code) }
    }

    fun save(then: (() -> Unit)? = null) = app.runTask(if (then == null) "Saved" else null, "Couldn't save the shader") {
        app.shaders.save(item, code)
        if (name.isNotBlank() && name != item.filename) app.db.rename(item.id, name.trim())
        app.db.updateItemOptions(item.id, interactive, audio)
        saved = code
        app.libraryChanged()
        then?.invoke()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { save() }, enabled = code != saved || name != item.filename ||
                interactive != item.interactive || audio != item.audio) { Text("Save") }
            Button(onClick = {
                save {
                    val fresh = app.db.getMedia(item.id) ?: return@save
                    app.setDesktop(fresh)
                }
            }) { Text("Set as wallpaper") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Switch(interactive, { interactive = it })
            Text("React to the mouse (iMouse)", color = Theme.colors.text, fontSize = 13.sp)
            Switch(audio, { audio = it })
            Text("React to audio (iChannel0)", color = Theme.colors.text, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = Theme.colors.danger) }
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(
                    Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Theme.colors.previewBg)
                        .border(1.dp, Theme.colors.cardBorder, RoundedCornerShape(6.dp)),
                ) {
                    BasicTextField(
                        code, { code = it },
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = androidx.compose.ui.graphics.Color(0xFFE6E6E6)),
                        cursorBrush = SolidColor(Theme.colors.accent),
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                val error = status?.takeIf { it.startsWith("error:") }?.removePrefix("error:")?.trim()
                Text(
                    error ?: if (status == "ok") "Compiles fine" else "Compiling…",
                    color = if (error != null) Theme.colors.danger else Theme.colors.textMuted, fontSize = 12.sp, maxLines = 3,
                )
            }
            Column(Modifier.weight(1f)) {
                WebPreview(app, previewUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))) { status = it }
                Spacer(Modifier.height(8.dp))
                app.webEngineStatus?.let { MutedText(it) }
                MutedText("Write a mainImage(out vec4 fragColor, in vec2 fragCoord) function. Available: iResolution, iTime, " +
                    "iTimeDelta, iFrame, iMouse, iDate and iChannel0 (audio: spectrum in row 0).")
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog("Delete “${item.filename}”?", "The shader is removed from your library and its code is deleted.", "Delete", danger = true,
            onDismiss = { confirmDelete = false }, onConfirm = {
                app.runTask("Shader deleted") {
                    if (app.wallpapers.state.mediaId == item.id) app.changeWallpaper { app.wallpapers.stopVideo() }
                    app.library.deleteThumbnail(item)
                    app.db.removeMedia(item.id)
                    item.localPath?.let { java.nio.file.Files.deleteIfExists(it) }
                    app.libraryChanged()
                }
            })
    }
}

private val BLANK_SHADER = """
// Your shader. fragCoord is the pixel position, iResolution the screen size, iTime the seconds since start.
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    vec3 color = 0.5 + 0.5 * cos(iTime + uv.xyx + vec3(0.0, 2.0, 4.0));
    fragColor = vec4(color, 1.0);
}
""".trimStart()
