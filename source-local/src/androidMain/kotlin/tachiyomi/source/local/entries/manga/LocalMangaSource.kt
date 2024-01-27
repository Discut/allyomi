package tachiyomi.source.local.entries.manga

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.storage.EpubFile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import nl.adaptivity.xmlutil.AndroidXmlReader
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.i18n.stringResource
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.comicinfo.copyFromComicInfo
import tachiyomi.core.metadata.comicinfo.getComicInfo
import tachiyomi.core.metadata.tachiyomi.ChapterDetails
import tachiyomi.core.metadata.tachiyomi.MangaDetails
import tachiyomi.core.storage.UniFileTempFileManager
import tachiyomi.core.storage.extension
import tachiyomi.core.storage.nameWithoutExtension
import tachiyomi.core.util.lang.withIOContext
import tachiyomi.core.util.system.ImageUtil
import tachiyomi.core.util.system.logcat
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.chapter.service.ChapterRecognition
import tachiyomi.i18n.MR
import tachiyomi.source.local.filter.manga.MangaOrderBy
import tachiyomi.source.local.image.manga.LocalMangaCoverManager
import tachiyomi.source.local.io.ArchiveManga
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.io.manga.LocalMangaSourceFileSystem
import tachiyomi.source.local.metadata.fillMetadata
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.math.abs
import com.github.junrar.Archive as JunrarArchive

actual class LocalMangaSource(
    private val context: Context,
    private val fileSystem: LocalMangaSourceFileSystem,
    private val coverManager: LocalMangaCoverManager,
) : CatalogueSource, UnmeteredSource {

    private val json: Json by injectLazy()
    private val xml: XML by injectLazy()
    private val tempFileManager: UniFileTempFileManager by injectLazy()

    private val POPULAR_FILTERS = FilterList(MangaOrderBy.Popular(context))
    private val LATEST_FILTERS = FilterList(MangaOrderBy.Latest(context))

    override val name: String = context.stringResource(MR.strings.local_manga_source)

    override val id: Long = ID

    override val lang: String = "other"

    override fun toString() = name

    override val supportsLatest: Boolean = true

    // Browse related
    override suspend fun getPopularManga(page: Int) = getSearchManga(page, "", POPULAR_FILTERS)

    override suspend fun getLatestUpdates(page: Int) = getSearchManga(page, "", LATEST_FILTERS)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withIOContext {
        val lastModifiedLimit = if (filters === LATEST_FILTERS) {
            System.currentTimeMillis() - LATEST_THRESHOLD
        } else {
            0L
        }

        var mangaDirs = fileSystem.getFilesInBaseDirectory()
            // Filter out files that are hidden and is not a folder
            .filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }
            .distinctBy { it.name }
            .filter {
                if (lastModifiedLimit == 0L && query.isBlank()) {
                    true
                } else if (lastModifiedLimit == 0L) {
                    it.name.orEmpty().contains(query, ignoreCase = true)
                } else {
                    it.lastModified() >= lastModifiedLimit
                }
            }

        filters.forEach { filter ->
            when (filter) {
                is MangaOrderBy.Popular -> {
                    mangaDirs = if (filter.state!!.ascending) {
                        mangaDirs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() })
                    } else {
                        mangaDirs.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() })
                    }
                }
                is MangaOrderBy.Latest -> {
                    mangaDirs = if (filter.state!!.ascending) {
                        mangaDirs.sortedBy(UniFile::lastModified)
                    } else {
                        mangaDirs.sortedByDescending(UniFile::lastModified)
                    }
                }
                else -> {
                    /* Do nothing */
                }
            }
        }

        val mangas = mangaDirs
            .map { mangaDir ->
                async {
                    SManga.create().apply {
                        title = mangaDir.name.orEmpty()
                        url = mangaDir.name.orEmpty()

                        // Try to find the cover
                        coverManager.find(mangaDir.name.orEmpty())?.let {
                            thumbnail_url = it.uri.toString()
                        }
                    }
                }
            }
            .awaitAll()

        MangasPage(mangas, false)
    }

    // Manga details related
    override suspend fun getMangaDetails(manga: SManga): SManga = withIOContext {
        coverManager.find(manga.url)?.let {
            manga.thumbnail_url = it.uri.toString()
        }

        // Augment manga details based on metadata files
        try {
            val mangaDir by lazy { fileSystem.getMangaDirectory(manga.url) }
            val mangaDirFiles = fileSystem.getFilesInMangaDirectory(manga.url)

            val comicInfoFile = mangaDirFiles
                .firstOrNull { it.name == COMIC_INFO_FILE }
            val noXmlFile = mangaDirFiles
                .firstOrNull { it.name == ".noxml" }
            val legacyJsonDetailsFile = mangaDirFiles
                .firstOrNull { it.extension == "json" && it.nameWithoutExtension == "details" }

            when {
                // Top level ComicInfo.xml
                comicInfoFile != null -> {
                    noXmlFile?.delete()
                    setMangaDetailsFromComicInfoFile(comicInfoFile.openInputStream(), manga)
                }

                // Old custom JSON format
                // TODO: remove support for this entirely after a while
                legacyJsonDetailsFile != null -> {
                    json.decodeFromStream<MangaDetails>(legacyJsonDetailsFile.openInputStream()).run {
                        title?.let { manga.title = it }
                        author?.let { manga.author = it }
                        artist?.let { manga.artist = it }
                        description?.let { manga.description = it }
                        genre?.let { manga.genre = it.joinToString() }
                        status?.let { manga.status = it }
                    }
                    // Replace with ComicInfo.xml file
                    val comicInfo = manga.getComicInfo()
                    mangaDir
                        ?.createFile(COMIC_INFO_FILE)
                        ?.openOutputStream()
                        ?.use {
                            val comicInfoString = xml.encodeToString(ComicInfo.serializer(), comicInfo)
                            it.write(comicInfoString.toByteArray())
                            legacyJsonDetailsFile.delete()
                        }
                }

                // Copy ComicInfo.xml from chapter archive to top level if found
                noXmlFile == null -> {
                    val chapterArchives = mangaDirFiles
                        .filter(ArchiveManga::isSupported)
                        .toList()

                    val folderPath = mangaDir?.filePath

                    val copiedFile = copyComicInfoFileFromArchive(chapterArchives, folderPath)
                    if (copiedFile != null) {
                        setMangaDetailsFromComicInfoFile(copiedFile.inputStream(), manga)
                    } else {
                        // Avoid re-scanning
                        mangaDir?.createFile(".noxml")
                    }
                }
            }
        } catch (e: Throwable) {
            logcat(
                LogPriority.ERROR,
                e,
            ) { "Error setting manga details from local metadata for ${manga.title}" }
        }

        return@withIOContext manga
    }

    private fun copyComicInfoFileFromArchive(chapterArchives: List<UniFile>, folderPath: String?): File? {
        for (chapter in chapterArchives) {
            when (Format.valueOf(chapter)) {
                is Format.Zip -> {
                    ZipFile(tempFileManager.createTempFile(chapter)).use { zip: ZipFile ->
                        zip.getEntry(COMIC_INFO_FILE)?.let { comicInfoFile ->
                            zip.getInputStream(comicInfoFile).buffered().use { stream ->
                                return copyComicInfoFile(stream, folderPath)
                            }
                        }
                    }
                }
                is Format.Rar -> {
                    JunrarArchive(tempFileManager.createTempFile(chapter)).use { rar ->
                        rar.fileHeaders.firstOrNull { it.fileName == COMIC_INFO_FILE }?.let { comicInfoFile ->
                            rar.getInputStream(comicInfoFile).buffered().use { stream ->
                                return copyComicInfoFile(stream, folderPath)
                            }
                        }
                    }
                }
                else -> {}
            }
        }
        return null
    }

    private fun copyComicInfoFile(comicInfoFileStream: InputStream, folderPath: String?): File {
        return File("$folderPath/$COMIC_INFO_FILE").apply {
            outputStream().use { outputStream ->
                comicInfoFileStream.use { it.copyTo(outputStream) }
            }
        }
    }

    private fun setMangaDetailsFromComicInfoFile(stream: InputStream, manga: SManga) {
        val comicInfo = AndroidXmlReader(stream, StandardCharsets.UTF_8.name()).use {
            xml.decodeFromReader<ComicInfo>(it)
        }

        manga.copyFromComicInfo(comicInfo)
    }

    // Chapters
    override suspend fun getChapterList(manga: SManga): List<SChapter> = withIOContext {
        val chaptersData = fileSystem.getFilesInMangaDirectory(manga.url)
            .firstOrNull {
                it.extension == "json" && it.nameWithoutExtension == "chapters"
            }?.let { file ->
                runCatching {
                    json.decodeFromStream<List<ChapterDetails>>(file.openInputStream())
                }.getOrNull()
            }

        val chapters = fileSystem.getFilesInMangaDirectory(manga.url)
            // Only keep supported formats
            .filter { it.isDirectory || ArchiveManga.isSupported(it) }
            .map { chapterFile ->
                SChapter.create().apply {
                    url = "${manga.url}/${chapterFile.name}"
                    name = if (chapterFile.isDirectory) {
                        chapterFile.name
                    } else {
                        chapterFile.nameWithoutExtension
                    }.orEmpty()
                    date_upload = chapterFile.lastModified()

                    val chapterNumber = ChapterRecognition
                        .parseChapterNumber(manga.title, this.name, this.chapter_number.toDouble())
                        .toFloat()
                    chapter_number = chapterNumber

                    val format = Format.valueOf(chapterFile)
                    if (format is Format.Epub) {
                        EpubFile(tempFileManager.createTempFile(format.file)).use { epub ->
                            epub.fillMetadata(manga, this)
                        }
                    }

                    // Overwrite data from chapters.json file
                    chaptersData?.also { dataList ->
                        dataList.firstOrNull { it.chapter_number.equalsTo(chapterNumber) }?.also { data ->
                            data.name?.also { name = it }
                            data.date_upload?.also { date_upload = parseDate(it) }
                            scanlator = data.scanlator
                        }
                    }
                }
            }
            .sortedWith { c1, c2 ->
                val c = c2.chapter_number.compareTo(c1.chapter_number)
                if (c == 0) c2.name.compareToCaseInsensitiveNaturalOrder(c1.name) else c
            }

        // Copy the cover from the first chapter found if not available
        if (manga.thumbnail_url.isNullOrBlank()) {
            chapters.lastOrNull()?.let { chapter ->
                updateCover(chapter, manga)
            }
        }

        chapters
    }

    private fun parseDate(isoDate: String): Long {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).parse(isoDate)?.time ?: 0L
    }

    private fun Float.equalsTo(other: Float): Boolean {
        return abs(this - other) < 0.0001
    }

    // Filters
    override fun getFilterList() = FilterList(MangaOrderBy.Popular(context))

    // Unused stuff
    override suspend fun getPageList(chapter: SChapter) = throw UnsupportedOperationException(
        "Unused",
    )

    fun getFormat(chapter: SChapter): Format {
        try {
            val (mangaDirName, chapterName) = chapter.url.split('/', limit = 2)
            return fileSystem.getBaseDirectory()
                ?.findFile(mangaDirName, true)
                ?.findFile(chapterName, true)
                ?.let(Format.Companion::valueOf)
                ?: throw Exception(context.stringResource(MR.strings.chapter_not_found))
        } catch (e: Format.UnknownFormatException) {
            throw Exception(context.stringResource(MR.strings.local_invalid_format))
        } catch (e: Exception) {
            throw e
        }
    }

    private fun updateCover(chapter: SChapter, manga: SManga): UniFile? {
        return try {
            when (val format = getFormat(chapter)) {
                is Format.Directory -> {
                    val entry = format.file.listFiles()
                        ?.sortedWith { f1, f2 ->
                            f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(
                                f2.name.orEmpty(),
                            )
                        }
                        ?.find {
                            !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() }
                        }

                    entry?.let { coverManager.update(manga, it.openInputStream()) }
                }
                is Format.Zip -> {
                    ZipFile(tempFileManager.createTempFile(format.file)).use { zip ->
                        val entry = zip.entries().toList()
                            .sortedWith { f1, f2 ->
                                f1.name.compareToCaseInsensitiveNaturalOrder(
                                    f2.name,
                                )
                            }
                            .find {
                                !it.isDirectory && ImageUtil.isImage(it.name) {
                                    zip.getInputStream(
                                        it,
                                    )
                                }
                            }

                        entry?.let { coverManager.update(manga, zip.getInputStream(it)) }
                    }
                }
                is Format.Rar -> {
                    JunrarArchive(tempFileManager.createTempFile(format.file)).use { archive ->
                        val entry = archive.fileHeaders
                            .sortedWith { f1, f2 ->
                                f1.fileName.compareToCaseInsensitiveNaturalOrder(
                                    f2.fileName,
                                )
                            }
                            .find {
                                !it.isDirectory && ImageUtil.isImage(it.fileName) {
                                    archive.getInputStream(
                                        it,
                                    )
                                }
                            }

                        entry?.let { coverManager.update(manga, archive.getInputStream(it)) }
                    }
                }
                is Format.Epub -> {
                    EpubFile(tempFileManager.createTempFile(format.file)).use { epub ->
                        val entry = epub.getImagesFromPages()
                            .firstOrNull()
                            ?.let { epub.getEntry(it) }

                        entry?.let { coverManager.update(manga, epub.getInputStream(it)) }
                    }
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Error updating cover for ${manga.title}" }
            null
        }
    }

    companion object {
        const val ID = 0L
        const val HELP_URL = "https://aniyomi.org/help/guides/local-manga/"

        private val LATEST_THRESHOLD = TimeUnit.MILLISECONDS.convert(7, TimeUnit.DAYS)
    }
}

fun Manga.isLocal(): Boolean = source == LocalMangaSource.ID

fun MangaSource.isLocal(): Boolean = id == LocalMangaSource.ID