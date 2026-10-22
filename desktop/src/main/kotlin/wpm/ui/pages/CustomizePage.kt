package wpm.ui.pages

import wpm.tr
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import wpm.ui.components.Dot
import wpm.ui.components.EmptyState
import wpm.ui.components.Eyebrow
import wpm.ui.components.FoldShape
import wpm.ui.components.ListEntry
import wpm.ui.components.Tag
import wpm.ui.components.PropertyEditor
import wpm.media.parseShaderProperties
import wpm.ui.components.drawFoldFlap
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.UploadFile
import wpm.ui.components.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import wpm.ui.components.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import wpm.ui.components.Switch
import androidx.compose.material3.Text
import wpm.ui.components.TextButton
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
import wpm.ui.components.ButtonIcon
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

    fun create(name: String, code: String) = app.runTask(tr("Created “{0}”", name), tr("Couldn't create the shader")) {
        selectedId = app.shaders.create(name, code, interactive = "iMouse" in code, audio = "iChannel0" in code)
        app.libraryChanged()
    }

    PageScaffold(tr("Customize"), tr("Make your own animated wallpapers with shaders (Shadertoy-style GLSL)."), scroll = false, actions = {
        OutlinedButton(onClick = {
            val dialog = FileDialog(app.window as? Frame, tr("Import a shader"), FileDialog.LOAD).apply {
                file = SHADER_EXTENSIONS.joinToString(";") { "*.$it" }
            }
            dialog.isVisible = true
            dialog.files.firstOrNull()?.let { file ->
                app.runTask(tr("Shader imported"), tr("Couldn't import the shader")) {
                    selectedId = app.shaders.import(file.toPath())
                    app.libraryChanged()
                }
            }
        }) { ButtonIcon(Icons.Outlined.UploadFile); Text(tr("Import .glsl")) }
        Box {
            Button(onClick = { newMenu = true }) { ButtonIcon(Icons.Outlined.Add); Text(tr("New shader")) }
            DropdownMenu(newMenu, { newMenu = false }) {
                DropdownMenuItem(text = { Text(tr("Blank")) }, onClick = { newMenu = false; create(tr("My shader"), BLANK_SHADER) })
                HorizontalDivider()
                app.shaders.samples().forEach { sample ->
                    DropdownMenuItem(text = { Text(tr("Sample: {0}", sample.name)) }, onClick = { newMenu = false; create(sample.name, sample.code) })
                }
            }
        }
    }) {
        if (shaders.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                EmptyState(tr("No shaders yet"), tr("Shaders are small programs that draw animated wallpapers on your graphics card. ") +
                    tr("Start from a sample with “New shader”, or import a .glsl file (for example from Shadertoy)."))
            }
            return@PageScaffold
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(Modifier.width(230.dp).fillMaxHeight(), padding = PaddingValues(10.dp), fold = 14.dp) {
                Text(tr("YOUR SHADERS"), color = Theme.colors.textSubtle, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 10.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(shaders, key = { it.id }) { s ->
                        ListEntry(s.id == selectedId, { selectedId = s.id }) {
                            Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp),
                                tint = if (s.id == selectedId) Theme.colors.accent else Theme.colors.textMuted)
                            Spacer(Modifier.width(10.dp))
                            Text(s.filename, color = Theme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (s.id == selectedId) FontWeight.SemiBold else FontWeight.Normal)
                        }
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
    /** Bumped when a setting changes, so the preview is written again with it. */
    var settingsVersion by remember(item.id) { mutableStateOf(0) }
    val declared = remember(code) { parseShaderProperties(code) }

    LaunchedEffect(item.id) {
        code = withContext(Dispatchers.IO) { app.shaders.code(item) }
        saved = code
    }
    // Recompile the preview shortly after typing stops.
    LaunchedEffect(code, settingsVersion) {
        if (code.isEmpty()) return@LaunchedEffect
        delay(if (settingsVersion > 0) 250 else 700)
        previewUrl = withContext(Dispatchers.IO) { app.shaders.previewPage(code, mediaId = item.id) }
    }

    fun save(then: (() -> Unit)? = null) = app.runTask(if (then == null) tr("Saved") else null, tr("Couldn't save the shader")) {
        app.shaders.save(item, code)
        if (name.isNotBlank() && name != item.filename) app.db.rename(item.id, name.trim())
        app.db.updateItemOptions(item.id, interactive, audio)
        saved = code
        app.libraryChanged()
        then?.invoke()
    }

    val flapColor = Theme.colors.indigo
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(tr("Name")) }, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { save() }, enabled = code != saved || name != item.filename ||
                interactive != item.interactive || audio != item.audio) { Text(tr("Save")) }
            Button(onClick = {
                save {
                    val fresh = app.db.getMedia(item.id) ?: return@save
                    app.setDesktop(fresh)
                }
            }) { Text(tr("Set as wallpaper")) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(interactive, { interactive = it })
            Text(tr("React to the mouse (iMouse)"), color = Theme.colors.text, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Switch(audio, { audio = it })
            Text(tr("React to audio (iChannel0)"), color = Theme.colors.text, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { confirmDelete = true }, danger = true) { ButtonIcon(Icons.Outlined.Delete); Text(tr("Delete")) }
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().shadow(8.dp, FoldShape(18.dp, 4.dp), ambientColor = Theme.colors.shadow, spotColor = Theme.colors.shadow)
                        .clip(FoldShape(18.dp, 4.dp)).background(Theme.colors.previewBg)
                        .drawWithContent { drawContent(); drawFoldFlap(18.dp.toPx(), flapColor, androidx.compose.ui.graphics.Color.Black) },
                ) {
                    Row(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.04f)).padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tag(tr("GLSL"), Theme.colors.indigo)
                        Text("${code.lines().size} lines", color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
                        if (code != saved) Text(tr("• unsaved"), color = Theme.colors.gold, fontSize = 12.sp)
                    }
                    BasicTextField(
                        code, { code = it },
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = androidx.compose.ui.graphics.Color(0xFFE6E6E6)),
                        cursorBrush = SolidColor(Theme.colors.accent),
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                val error = status?.takeIf { it.startsWith("error:") }?.removePrefix("error:")?.trim()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(when {
                        error != null -> Theme.colors.danger
                        status == "ok" -> Theme.colors.teal
                        else -> Theme.colors.gold
                    })
                    Spacer(Modifier.width(8.dp))
                    Text(
                        error ?: if (status == "ok") tr("Compiles fine") else tr("Compiling…"),
                        color = if (error != null) Theme.colors.danger else Theme.colors.textMuted, fontSize = 12.sp, maxLines = 3,
                    )
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Eyebrow(tr("Live preview"), Theme.colors.indigo)
                WebPreview(app, previewUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) { status = it }
                Spacer(Modifier.height(8.dp))
                app.webEngineStatus?.let { MutedText(it) }
                MutedText(tr("Write a mainImage(out vec4 fragColor, in vec2 fragCoord) function. Available: iResolution, iTime, ") +
                    tr("iTimeDelta, iFrame, iMouse, iPointer (mouse 0..1), iDate and iChannel0 (audio: spectrum in row 0)."))
                MutedText(tr("Settings: a comment line such as // @slider speed 0 2 1 Speed, // @color tint #ff8800 Tint, ") +
                    tr("// @checkbox rain on Rain or // @image photo Picture (iChannel1-3) adds a control and a uniform of that name."))
                PropertyEditor(app, item, Modifier.padding(top = 12.dp), declared = declared) { settingsVersion++ }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(tr("Delete “{0}”?", item.filename), tr("The shader is removed from your library and its code is deleted."), tr("Delete"), danger = true,
            onDismiss = { confirmDelete = false }, onConfirm = {
                app.runTask(tr("Shader deleted")) {
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
