package wpm.media

import wpm.AppPaths
import wpm.Samples
import wpm.data.Database
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.moveTo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TagsAndMissingTest {
    private val dir: Path = Files.createTempDirectory("wpm-tags")
    private val paths = AppPaths(dir.resolve("data")).ensure()
    private val db = Database(paths.dbPath)
    private val library = MediaLibrary(db, paths.thumbDir)

    @AfterTest
    fun cleanup() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private fun import(vararg names: String): List<Long> =
        library.importFiles(names.map { Samples.image(dir.resolve("pictures/$it")) }).added

    @Test
    fun tagsFilterAndSearch() {
        val (beach, forest, city) = import("beach.png", "forest.png", "city.png")
        db.addTag(listOf(beach, forest), "Nature")
        db.addTag(listOf(forest), "  green   leaves ")
        db.addTag(listOf(city), "nature") // same tag, whatever the case
        assertEquals(listOf("green leaves" to 1, "Nature" to 3), db.listTags())
        assertEquals(listOf("green leaves", "Nature"), db.tagsOf(forest))
        assertEquals(setOf(beach, forest, city), db.listMedia(tag = "Nature").map { it.id }.toSet())
        assertEquals(listOf(forest), db.listMedia(search = "leaves").map { it.id }) // search finds tags too
        db.removeTag(forest, "green leaves")
        assertEquals(listOf("Nature" to 3), db.listTags()) // unused tags go away
        db.removeMedia(city)
        assertEquals(listOf("Nature" to 2), db.listTags())
        assertFailsWith<IllegalArgumentException> { db.addTag(listOf(beach), "   ") }
    }

    @Test
    fun findsMovedFilesAgain() {
        val (a, b, c) = import("a.png", "b.png", "c.png")
        val missing = MissingFiles(db)
        assertEquals(emptyList(), missing.find())
        // The user moves the whole folder; one file gets deleted.
        val moved = dir.resolve("moved").createDirectories().resolve("sub").also { it.createDirectories() }
        dir.resolve("pictures/a.png").moveTo(moved.resolve("a.png"))
        dir.resolve("pictures/b.png").moveTo(moved.resolve("b.png"))
        Files.delete(dir.resolve("pictures/c.png"))
        assertEquals(setOf(a, b, c), missing.find().map { it.id }.toSet())

        db.addTag(listOf(a), "keep")
        assertEquals(2, missing.searchFolder(dir.resolve("moved")))
        assertEquals(listOf(c), missing.find().map { it.id })
        assertEquals(moved.resolve("a.png").toString(), db.getMedia(a)!!.path)
        assertEquals(listOf("keep"), db.tagsOf(a)) // same item: tags, playlists and rules stay

        // Locating one by hand; a file already in the library can't be used twice.
        val elsewhere = Samples.image(dir.resolve("elsewhere/c-renamed.png"))
        missing.relink(db.getMedia(c)!!, elsewhere)
        assertEquals("c-renamed.png", db.getMedia(c)!!.filename)
        assertFailsWith<IllegalArgumentException> { missing.relink(db.getMedia(b)!!, moved.resolve("a.png")) }
        assertTrue(missing.find().isEmpty())
    }
}
