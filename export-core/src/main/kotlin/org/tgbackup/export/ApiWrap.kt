// Port of tdesktop Telegram/SourceFiles/export/export_api_wrap.cpp (the
// single chat / single topic paths), written as coroutines instead of
// callbacks but sending the same requests in the same order.
@file:Suppress("FunctionName")

package org.tgbackup.export

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.tgbackup.export.data.*
import org.tgbackup.export.output.Stats
import org.tgbackup.mtproto.MtpClient
import org.tgbackup.mtproto.RpcError
import org.tgbackup.mtproto.tl.*
import java.util.TreeMap
import java.util.TreeSet

/** AyuGram's values (Telegram Desktop uses 128 KB and 2). */
internal const val kFileChunkSize = 8 * 128 * 1024
internal const val kFileRequestsCount = 3
private const val kMessagesSliceLimit = 100
private const val kFileMaxSize = 4000L * 1024 * 1024
private const val kLocationCacheSize = 100_000
private const val kMaxEmojiPerRequest = 100

/** MTP::Error as the controller sees it. */
class ExportApiError(val code: Int, val type: String) : Exception("$code $type")

/** Thrown when a progress callback returns false (export stopped). */
class ExportStopped : Exception()

class StartInfo(var userpicsCount: Int = 0, var storiesCount: Int = 0, var profileMusicCount: Int = 0, var dialogsCount: Int = 0)

class DownloadProgress(
    val randomId: Long = 0,
    val path: String = "",
    val itemIndex: Int = 0,
    val ready: Long = 0,
    val total: Long = 0,
)

private class FileProgress(val ready: Long, val total: Long)

private data class LocationKey(val type: Long, val id: Long) : Comparable<LocationKey> {
    override fun compareTo(other: LocationKey): Int {
        val t = java.lang.Long.compareUnsigned(type, other.type)
        return if (t != 0) t else java.lang.Long.compareUnsigned(id, other.id)
    }
}

private fun ComputeLocationKey(value: FileLocation): LocationKey {
    var type = value.dcId.toLong()
    var id = 0L
    when (val data = value.data) {
        is MTPDinputDocumentFileLocation -> {
            val letter = if (data.vthumb_size.isEmpty()) 0 else data.vthumb_size.toByteArray()[0].toInt()
            type = type or (2L shl 24)
            type = type or ((letter.toLong() and 0xffffffffL) shl 16)
            id = data.vid
        }
        is MTPDinputPhotoFileLocation -> {
            val letter = if (data.vthumb_size.isEmpty()) 0 else data.vthumb_size.toByteArray()[0].toInt()
            type = type or (6L shl 24)
            type = type or ((letter.toLong() and 0xffffffffL) shl 16)
            id = data.vid
        }
        MTPDinputTakeoutFileLocation -> type = type or (5L shl 24)
        else -> throw IllegalStateException("File location type in Export::ComputeLocationKey.")
    }
    return LocationKey(type, id)
}

private class LoadedFileCache(private val limit: Int) {
    private val map = TreeMap<LocationKey, String>()
    private val list = ArrayDeque<LocationKey>()

    fun save(location: FileLocation, relativePath: String) {
        if (!location.isValid) return
        val key = ComputeLocationKey(location)
        map[key] = relativePath
        list.addLast(key)
        if (list.size > limit) map.remove(list.removeFirst())
    }

    fun find(location: FileLocation): String? = if (!location.isValid) null else map[ComputeLocationKey(location)]
}

private fun DocumentMediaType(document: Document): Int = when {
    document.isSticker -> MediaSettings.Type.Sticker
    document.isVideoMessage -> MediaSettings.Type.VideoMessage
    document.isVoiceMessage -> MediaSettings.Type.VoiceMessage
    document.isAnimated -> MediaSettings.Type.GIF
    document.isVideoFile -> MediaSettings.Type.Video
    else -> MediaSettings.Type.File
}

private fun ExtractFullRichMessage(result: MTPmessages_Messages, messageId: Int): MTPRichMessage? {
    var matches = 0
    var richMessage: MTPRichMessage? = null
    val list = when (result) {
        is MTPDmessages_messagesNotModified -> emptyList()
        is MTPDmessages_messages -> result.vmessages
        is MTPDmessages_messagesSlice -> result.vmessages
        is MTPDmessages_channelMessages -> result.vmessages
    }
    for (entry in list) {
        val id = when (entry) {
            is MTPDmessage -> entry.vid
            is MTPDmessageService -> entry.vid
            is MTPDmessageEmpty -> entry.vid
        }
        if (id != messageId) continue
        ++matches
        if (entry is MTPDmessage) {
            val candidate = entry.vrich_message
            if (candidate != null && !(candidate as MTPDrichMessage).is_part) richMessage = candidate
        }
    }
    return if (matches != 1) null else richMessage
}

private fun RefreshRichMessageFileReference(location: FileLocation, message: RichMessage): FileLocation? {
    val refreshed = location.copy()
    for (photo in message.photos.values) {
        if (RefreshFileReference(refreshed, photo.image.file.location)) return refreshed
    }
    for (document in message.documents.values) {
        if (RefreshFileReference(refreshed, document.file.location)) return refreshed
        if (document.thumb.width > 0 && RefreshFileReference(refreshed, document.thumb.file.location)) return refreshed
    }
    return null
}

private class RichVisitor(
    val text: (RichText) -> Unit,
    val photo: (Photo) -> Unit,
    val document: (Document) -> Unit,
)

private fun VisitRichPhoto(message: RichMessage, id: Long, v: RichVisitor) {
    message.photos[id.toULong()]?.let { v.photo(it) }
}

private fun VisitRichDocument(message: RichMessage, id: Long, v: RichVisitor) {
    message.documents[id.toULong()]?.let { v.document(it) }
}

private fun VisitRichText(message: RichMessage, text: RichText, v: RichVisitor) {
    v.text(text)
    if (text.type == RichText.Type.InlineImage) VisitRichDocument(message, text.id, v)
    for (child in text.children) VisitRichText(message, child, v)
    for (child in text.oldChildren) VisitRichText(message, child, v)
}

private fun VisitRichCaption(message: RichMessage, caption: RichCaption, v: RichVisitor) {
    VisitRichText(message, caption.text, v)
    VisitRichText(message, caption.credit, v)
}

private fun VisitRichBlocks(message: RichMessage, blocks: List<RichBlock>, v: RichVisitor) {
    for (block in blocks) VisitRichBlock(message, block, v)
}

private fun VisitRichBlock(message: RichMessage, block: RichBlock, v: RichVisitor) {
    when (block.kind) {
        RichBlock.Kind.Heading, RichBlock.Kind.Paragraph, RichBlock.Kind.Footer, RichBlock.Kind.Thinking,
        RichBlock.Kind.AuthorDate, RichBlock.Kind.Code,
        -> VisitRichText(message, block.text, v)
        RichBlock.Kind.List -> for (item in block.listItems) {
            when (item.content) {
                RichListItemContent.Text -> item.text?.let { VisitRichText(message, it, v) }
                RichListItemContent.Blocks -> VisitRichBlocks(message, item.blocks, v)
            }
        }
        RichBlock.Kind.Quote -> {
            when (block.quoteContent) {
                RichQuoteContent.Text -> VisitRichText(message, block.text, v)
                RichQuoteContent.Blocks -> VisitRichBlocks(message, block.blocks, v)
            }
            VisitRichText(message, block.quoteCaption, v)
        }
        RichBlock.Kind.Photo -> {
            VisitRichPhoto(message, block.photoId, v)
            VisitRichCaption(message, block.caption, v)
        }
        RichBlock.Kind.Video, RichBlock.Kind.Audio, RichBlock.Kind.File -> {
            VisitRichDocument(message, block.documentId, v)
            VisitRichCaption(message, block.caption, v)
        }
        RichBlock.Kind.Cover -> {
            check(block.blocks.size == 1)
            VisitRichBlock(message, block.blocks.first(), v)
        }
        RichBlock.Kind.Embed -> {
            block.posterPhotoId?.let { VisitRichPhoto(message, it, v) }
            VisitRichCaption(message, block.caption, v)
        }
        RichBlock.Kind.EmbedPost -> {
            VisitRichPhoto(message, block.authorPhotoId, v)
            VisitRichBlocks(message, block.blocks, v)
            VisitRichCaption(message, block.caption, v)
        }
        RichBlock.Kind.Collage, RichBlock.Kind.Slideshow -> {
            VisitRichBlocks(message, block.blocks, v)
            VisitRichCaption(message, block.caption, v)
        }
        RichBlock.Kind.Table -> {
            VisitRichText(message, block.text, v)
            for (row in block.tableRows) for (cell in row.cells) cell.text?.let { VisitRichText(message, it, v) }
        }
        RichBlock.Kind.Details -> {
            VisitRichText(message, block.text, v)
            VisitRichBlocks(message, block.blocks, v)
        }
        RichBlock.Kind.RelatedArticles -> {
            VisitRichText(message, block.text, v)
            for (article in block.relatedArticles) article.photoId?.let { VisitRichPhoto(message, it, v) }
        }
        RichBlock.Kind.Map, RichBlock.Kind.InputMap -> VisitRichCaption(message, block.caption, v)
        RichBlock.Kind.ButtonRow -> for (button in block.buttons) VisitRichText(message, button, v)
        RichBlock.Kind.Unsupported, RichBlock.Kind.Divider, RichBlock.Kind.Anchor, RichBlock.Kind.Channel,
        RichBlock.Kind.Math, RichBlock.Kind.Unknown,
        -> Unit
    }
}

private fun VisitRichMessage(message: RichMessage, v: RichVisitor) = VisitRichBlocks(message, message.blocks, v)

private fun ULongOf(s: String): Long = s.toULongOrNull()?.toLong() ?: 0L

class ApiWrap(private val client: MtpClient, private val log: (String) -> Unit = {}) {
    private var takeoutId: Long? = null
    private var selfId: Long? = null
    private var stats: Stats? = null
    private lateinit var settings: Settings
    private val user: MTPInputUser = MTPDinputUserSelf
    private val fileCache = LoadedFileCache(kLocationCacheSize)
    private var splits: MutableList<MTPMessageRange> = ArrayList()

    /** flat_set<uint64>: sorted as unsigned. */
    private val unresolvedCustomEmoji = TreeSet<ULong>()
    private val resolvedCustomEmoji = HashMap<Long, Document>()

    // ---- current file (for skipFile) ----
    @Volatile
    private var currentFileRandomId: Long = 0

    @Volatile
    private var currentFileJob: Job? = null

    @Volatile
    private var currentFileSkipped = false

    // ---- requests -------------------------------------------------------

    private fun apiError(e: RpcError): ExportApiError = ExportApiError(e.code, e.type)

    private fun error(text: String): Nothing = throw ExportApiError(0, "API_ERROR: $text")

    private suspend fun <R> mainRequest(request: TlFunction<R>): R = client.invoke(InvokeWithTakeout(takeoutId!!, request))

    private suspend fun <R> splitRequest(index: Int, request: TlFunction<R>): R {
        check(index < splits.size)
        return mainRequest(InvokeWithMessagesRange(splits[index], request))
    }

    private suspend fun <R> rethrow(block: suspend () -> R): R = try {
        block()
    } catch (e: RpcError) {
        throw apiError(e)
    }

    // ---- start ------------------------------------------------------------

    suspend fun startExport(settings: Settings, stats: Stats?): StartInfo = rethrow {
        this.settings = settings.copy()
        this.stats = stats
        val info = StartInfo()
        startMainSession()
        // Single chat export: only the chats steps are used.
        if ((this.settings.types and Settings.Type.Userpics) != 0 ||
            (this.settings.types and Settings.Type.Stories) != 0 ||
            (this.settings.types and Settings.Type.ProfileMusic) != 0
        ) throw UnsupportedOperationException("Only single chat export is supported.")
        if ((this.settings.types and Settings.Type.AnyChatsMask) != 0) {
            requestSplitRanges(info)
            requestDialogsCount(info)
        }
        info
    }

    private suspend fun startMainSession() {
        val T = Settings.Type
        val sizeLimit = settings.media.sizeLimit
        val hasFiles = (settings.media.types != 0 && sizeLimit > 0) ||
            (settings.types and T.Userpics) != 0 || (settings.types and T.Stories) != 0
        val users = client.invoke(MTPusers_GetUsers(listOf(MTPDinputUserSelf)))
        for (u in users) if (u is MTPDuser && u.is_self) selfId = u.vid
        if (selfId == null) error("Could not retrieve selfId.")
        // f_files and f_file_max_size are the same bit (flags.5).
        val request = MTPaccount_InitTakeoutSession(
            is_contacts = (settings.types and T.Contacts) != 0,
            is_message_users = (settings.types and (T.PersonalChats or T.BotChats)) != 0,
            is_message_chats = (settings.types and T.PrivateGroups) != 0,
            is_message_megagroups = (settings.types and (T.PrivateGroups or T.PublicGroups)) != 0,
            is_message_channels = (settings.types and (T.PrivateChannels or T.PublicChannels)) != 0,
            is_files = hasFiles,
            vfile_max_size = if (hasFiles) sizeLimit else null,
        )
        val result = client.invoke(request) as MTPDaccount_takeout
        takeoutId = result.vid
        @Suppress("UNUSED_VARIABLE") val unused = kFileMaxSize
    }

    private fun useOnlyLastSplit(): Boolean = (settings.types and Settings.Type.NonChannelChatsMask) == 0

    private suspend fun requestSplitRanges(info: StartInfo) {
        splits = mainRequest(MTPmessages_GetSplitRanges()).toMutableList()
        if (splits.isEmpty()) splits.add(MTPDmessageRange(1, Int.MAX_VALUE))
        @Suppress("UNUSED_VARIABLE") val splitIndex = if (useOnlyLastSplit()) splits.size - 1 else 0
        @Suppress("UNUSED_VARIABLE") val unused = info
    }

    private fun requestDialogsCount(info: StartInfo) {
        check(settings.onlySinglePeer())
        info.dialogsCount = if (settings.singlePeer is MTPDinputPeerChannel) 1 else splits.size
    }

    // ---- dialogs list (single peer) ---------------------------------------

    private class ChatsProcess {
        val info = DialogsInfo()
        var processedCount = 0
        val indexByPeer = TreeMap<PeerId, Int>()
        var splitIndexPlusOne = 0
    }

    suspend fun requestDialogsList(progress: (Int) -> Boolean): DialogsInfo = rethrow {
        val process = ChatsProcess()
        process.splitIndexPlusOne = splits.size
        requestSinglePeerDialog(process, progress)
        FinalizeDialogsInfo(process.info, settings)
        process.info
    }

    private suspend fun requestSinglePeerDialog(process: ChatsProcess, progress: (Int) -> Boolean) {
        val peer = settings.singlePeer
        val info = when (peer) {
            is MTPDinputPeerUser -> ParseDialogsInfo(peer, mainRequest(MTPusers_GetUsers(listOf(MTPDinputUser(peer.vuser_id, peer.vaccess_hash)))))
            is MTPDinputPeerChat -> ParseDialogsInfo(peer, mainRequest(MTPmessages_GetChats(listOf(peer.vchat_id))))
            is MTPDinputPeerChannel -> ParseDialogsInfo(
                peer,
                mainRequest(MTPchannels_GetChannels(listOf(MTPDinputChannel(peer.vchannel_id, peer.vaccess_hash)))),
            )
            MTPDinputPeerSelf -> ParseDialogsInfo(peer, mainRequest(MTPusers_GetUsers(listOf(MTPDinputUserSelf))))
            else -> throw IllegalStateException("Peer in ApiWrap::requestSinglePeerDialog.")
        }
        appendSinglePeerDialogs(process, info, progress)
    }

    private suspend fun appendSinglePeerDialogs(process: ChatsProcess, info: DialogsInfo, progress: (Int) -> Boolean) {
        fun isSupergroupType(t: DialogInfo.Type) = t == DialogInfo.Type.PrivateSupergroup || t == DialogInfo.Type.PublicSupergroup
        fun isChannelType(t: DialogInfo.Type) = t == DialogInfo.Type.PrivateChannel || t == DialogInfo.Type.PublicChannel
        var migrated: DialogInfo? = null
        val last = process.splitIndexPlusOne - 1
        for (chat in info.chats) {
            if (isSupergroupType(chat.type) && migrated == null) {
                migrated = chat
                continue
            } else if (isChannelType(chat.type) || chat.isMonoforum) {
                continue
            }
            for (i in last downTo 1) {
                chat.splits.add(i - 1)
                chat.messagesCountPerSplit.add(0)
            }
        }
        if (migrated == null) process.processedCount += info.chats.size
        appendChatsSlice(process, process.info.chats, info.chats, process.splitIndexPlusOne - 1)
        if (migrated != null) {
            // requestSinglePeerMigrated: its answer arrives after the slice above is appended.
            appendSinglePeerDialogs(process, requestSinglePeerMigrated(migrated), progress)
            return
        }
        if (!progress(process.processedCount)) throw ExportStopped()
    }

    private suspend fun requestSinglePeerMigrated(info: DialogInfo): DialogsInfo {
        val input = when (val p = info.input) {
            is MTPDinputPeerChannel -> MTPDinputChannel(p.vchannel_id, p.vaccess_hash)
            else -> throw IllegalStateException("Peer type in a supergroup.")
        }
        val result = mainRequest(MTPchannels_GetFullChannel(input)) as MTPDmessages_chatFull
        val migratedChatId = (result.vfull_chat as? MTPDchannelFull)?.vmigrated_from_chat_id ?: 0L
        return if (migratedChatId != 0L) {
            ParseDialogsInfo(MTPDinputPeerChat(migratedChatId), MTPDmessages_chats(result.vchats))
        } else {
            DialogsInfo()
        }
    }

    private fun SettingsFromDialogsType(type: DialogInfo.Type): Int = when (type) {
        DialogInfo.Type.Self, DialogInfo.Type.Personal -> Settings.Type.PersonalChats
        DialogInfo.Type.Bot -> Settings.Type.BotChats
        DialogInfo.Type.PrivateGroup, DialogInfo.Type.PrivateSupergroup -> Settings.Type.PrivateGroups
        DialogInfo.Type.PublicSupergroup -> Settings.Type.PublicGroups
        DialogInfo.Type.PrivateChannel -> Settings.Type.PrivateChannels
        DialogInfo.Type.PublicChannel -> Settings.Type.PublicChannels
        else -> 0
    }

    private fun appendChatsSlice(process: ChatsProcess, to: MutableList<DialogInfo>, from: List<DialogInfo>, splitIndex: Int) {
        val types = settings.types
        fun goodByTypes(info: DialogInfo) = (types and SettingsFromDialogsType(info.type)) != 0
        val filtered = from.filter { info ->
            goodByTypes(info) || (info.migratedToChannelId != 0L &&
                ((types and Settings.Type.PublicGroups) != 0 || (types and Settings.Type.PrivateGroups) != 0))
        }
        for (info in filtered) {
            val nextIndex = to.size
            if (info.migratedToChannelId != 0L) {
                val toPeerId = Peer_.fromChannel(info.migratedToChannelId)
                val i = process.indexByPeer[toPeerId]
                if (i != null && AddMigrateFromSlice(to[i], info, splitIndex, splits.size)) {
                    continue
                } else if (!goodByTypes(info)) {
                    continue
                }
            }
            val existing = process.indexByPeer[info.peerId]
            val index = if (existing == null) {
                process.indexByPeer[info.peerId] = nextIndex
                to.add(info)
                nextIndex
            } else existing
            to[index].splits.add(splitIndex)
            to[index].messagesCountPerSplit.add(0)
        }
    }

    // ---- messages ---------------------------------------------------------

    private abstract class AbstractMessagesProcess {
        lateinit var fileProgress: (DownloadProgress) -> Boolean
        lateinit var handleSlice: (MessagesSlice) -> Boolean
        val context = ParseMediaContext()
        var slice: MessagesSlice? = null
        var lastSlice = false
        var fileIndex = 0
    }

    private class ChatProcess : AbstractMessagesProcess() {
        lateinit var info: DialogInfo
        var localSplitIndex = 0
        var largestIdPlusOne = 1
    }

    private class TopicProcess : AbstractMessagesProcess() {
        var peerId: PeerId = 0
        lateinit var inputPeer: MTPInputPeer
        var topicRootId = 0
        var relativePath = ""
        var offsetId = 0
        var totalCount = 0
        var processedCount = 0
    }

    private var chatProcess: ChatProcess? = null
    private var topicProcess: TopicProcess? = null

    suspend fun requestMessages(
        info: DialogInfo,
        start: (DialogInfo) -> Boolean,
        progress: (DownloadProgress) -> Boolean,
        slice: (MessagesSlice) -> Boolean,
    ) = rethrow {
        val process = ChatProcess()
        process.context.selfPeerId = Peer_.fromUser(selfId!!)
        process.info = info
        process.fileProgress = progress
        process.handleSlice = slice
        chatProcess = process
        try {
            for (localSplitIndex in info.splits.indices) requestMessagesCount(process, localSplitIndex)
            if (!start(process.info)) throw ExportStopped()
            while (true) {
                requestMessagesSlice(process)
                if (!finishMessagesSlice(process)) break
            }
        } finally {
            chatProcess = null
        }
    }

    private fun countOf(result: MTPmessages_Messages): Int = when (result) {
        is MTPDmessages_messages -> result.vmessages.size
        is MTPDmessages_messagesSlice -> result.vcount
        is MTPDmessages_channelMessages -> result.vcount
        is MTPDmessages_messagesNotModified -> -1
    }

    private suspend fun requestMessagesCount(process: ChatProcess, localSplitIndex: Int) {
        val result = requestChatMessages(process, process.info.splits[localSplitIndex], 0, 0, 1)
        val count = countOf(result)
        if (count < 0) error("Unexpected messagesNotModified received.")
        val skipSplit = !SingleMessageAfter(result, settings.singlePeerFrom)
        if (skipSplit) {
            // No messages from the requested range, skip this split.
            process.info.messagesCountPerSplit[localSplitIndex] = 0
            return
        }
        // checkFirstMessageDate
        if (settings.singlePeerTill <= 0) {
            process.info.messagesCountPerSplit[localSplitIndex] = count
            return
        }
        val first = requestChatMessages(process, process.info.splits[localSplitIndex], 1, -1, 1)
        val skip = !SingleMessageBefore(first, settings.singlePeerTill)
        process.info.messagesCountPerSplit[localSplitIndex] = if (skip) 0 else count
    }

    private suspend fun requestChatMessages(
        process: ChatProcess,
        splitIndex: Int,
        offsetId: Int,
        addOffset: Int,
        limit: Int,
    ): MTPmessages_Messages {
        val splitsCount = splits.size
        val realPeerInput = if (splitIndex >= 0) process.info.input else process.info.migratedFromInput
        val outgoingInput = if (process.info.isMonoforum) process.info.monoforumBroadcastInput else MTPDinputPeerSelf
        val realSplitIndex = if (splitIndex >= 0) splitIndex else splitsCount + splitIndex
        if (process.info.onlyMyMessages) {
            return splitRequest(
                realSplitIndex,
                MTPmessages_Search(
                    vpeer = realPeerInput,
                    vq = "",
                    vfrom_id = outgoingInput,
                    vfilter = MTPDinputMessagesFilterEmpty,
                    vmin_date = 0,
                    vmax_date = 0,
                    voffset_id = offsetId,
                    vadd_offset = addOffset,
                    vlimit = limit,
                    vmax_id = 0,
                    vmin_id = 0,
                    vhash = 0,
                ),
            )
        }
        return try {
            splitRequest(realSplitIndex, MTPmessages_GetHistory(realPeerInput, offsetId, 0, addOffset, limit, 0, 0, 0))
        } catch (e: RpcError) {
            if (e.type == "CHANNEL_PRIVATE" && realPeerInput is MTPDinputPeerChannel && !process.info.onlyMyMessages) {
                // Perhaps we just left / were kicked from channel.
                // Just switch to only my messages.
                process.info.onlyMyMessages = true
                requestChatMessages(process, splitIndex, offsetId, addOffset, limit)
            } else {
                throw e
            }
        }
    }

    private suspend fun requestMessagesSlice(process: ChatProcess) {
        val count = process.info.messagesCountPerSplit[process.localSplitIndex]
        if (count == 0) {
            startMessagesSlice(process, MessagesSlice())
            return
        }
        val result = requestChatMessages(
            process,
            process.info.splits[process.localSplitIndex],
            process.largestIdPlusOne,
            -kMessagesSliceLimit,
            kMessagesSliceLimit,
        )
        val (messages, users, chats) = when (result) {
            is MTPDmessages_messagesNotModified -> error("Unexpected messagesNotModified received.")
            is MTPDmessages_messages -> {
                process.lastSlice = true
                Triple(result.vmessages, result.vusers, result.vchats)
            }
            is MTPDmessages_messagesSlice -> Triple(result.vmessages, result.vusers, result.vchats)
            is MTPDmessages_channelMessages -> Triple(result.vmessages, result.vusers, result.vchats)
        }
        startMessagesSlice(process, ParseMessagesSlice(process.context, messages, users, chats, process.info.relativePath))
    }

    /** startMessagesSlice + resumeMessagesSlice + the custom emoji / file loading. */
    private suspend fun startMessagesSlice(process: AbstractMessagesProcess, slice: MessagesSlice) {
        check(process.slice == null)
        if (slice.list.isEmpty()) process.lastSlice = true
        process.slice = slice
        process.fileIndex = 0
        val topic = process is TopicProcess
        val list = slice.list
        // Hydrate partial rich messages.
        for (index in list.indices) {
            val message = list[index]
            if (SkipMessageByDate(message, settings)) continue
            val rich = message.richMessage
            if (rich == null || !rich.part) continue
            val rawId = message.id
            var peer = if (topic) (process as TopicProcess).inputPeer else (process as ChatProcess).info.input
            if (!topic) {
                val p = process as ChatProcess
                if (p.info.splits[p.localSplitIndex] < 0) peer = p.info.migratedFromInput
            }
            val result = mainRequest(MTPmessages_GetRichMessage(peer, rawId))
            hydrateMessageDone(process, index, rawId, result)
        }
        collectMessagesCustomEmoji(slice)
        resolveCustomEmoji(process)
        loadMessageFiles(process)
    }

    private fun hydrateMessageDone(process: AbstractMessagesProcess, index: Int, rawId: Int, result: MTPmessages_Messages) {
        val richMessage = ExtractFullRichMessage(result, rawId) ?: error("Unexpected rich message hydration result.")
        val list = process.slice!!.list
        val message = list[index]
        if (message.id != rawId || message.richMessage?.part != true) error("Unexpected rich message hydration result.")
        val relativePath = if (process is TopicProcess) process.relativePath else (process as ChatProcess).info.relativePath
        val parsed = ParseRichMessage(process.context, richMessage, relativePath, message.date)
        if (parsed.part) error("Unexpected rich message hydration result.")
        message.richMessage = parsed
    }

    private fun collectMessagesCustomEmoji(slice: MessagesSlice) {
        fun collect(id: Long) {
            if (id != 0L && !resolvedCustomEmoji.containsKey(id)) unresolvedCustomEmoji.add(id.toULong())
        }
        for (message in slice.list) {
            if (SkipMessageByDate(message, settings)) continue
            for (part in message.text) if (part.type == TextPart.Type.CustomEmoji) collect(ULongOf(part.additional))
            for (reaction in message.reactions) if (reaction.type == Reaction.Type.CustomEmoji) collect(ULongOf(reaction.documentId))
            message.richMessage?.let { rich ->
                VisitRichMessage(rich, RichVisitor({ if (it.type == RichText.Type.CustomEmoji) collect(it.id) }, {}, {}))
            }
        }
    }

    private suspend fun resolveCustomEmoji(process: AbstractMessagesProcess) {
        while (unresolvedCustomEmoji.isNotEmpty()) {
            val count = minOf(unresolvedCustomEmoji.size, kMaxEmojiPerRequest)
            val all = unresolvedCustomEmoji.toList()
            val v = all.subList(all.size - count, all.size).map { it.toLong() }
            for (id in v) unresolvedCustomEmoji.remove(id.toULong())
            try {
                val result = mainRequest(MTPmessages_GetCustomEmojiDocuments(v))
                val relativePath = if (process is TopicProcess) process.relativePath else (process as ChatProcess).info.relativePath
                for (entry in result) {
                    val document = ParseDocument(process.context, entry, relativePath, 0)
                    resolvedCustomEmoji.putIfAbsent(document.id, document)
                }
            } catch (e: RpcError) {
                log("Export Error: Failed to get documents for emoji.")
            }
            for (id in v) {
                if (resolvedCustomEmoji.containsKey(id)) continue
                resolvedCustomEmoji[id] = Document().apply { file.skipReason = File.SkipReason.Unavailable }
            }
        }
    }

    private class FilePolicy(val message: Message, val type: Int, val controllingSize: Long)

    private class MessageFileWork(val file: File, val controllingSize: Long, val type: Int, val rich: Boolean)

    private suspend fun getCustomEmoji(process: AbstractMessagesProcess, index: Int, message: Message, data: String): String {
        val id = ULongOf(data)
        if (id == 0L) return data
        val document = resolvedCustomEmoji[id] ?: return TextPart.UnavailableEmoji()
        val file = document.file
        val policy = FilePolicy(message, DocumentMediaType(document), document.file.size)
        processFileLoad(file, FileOrigin().apply { customEmojiId = id }, { loadFileProgress(process, index, it) }, policy)
        return when (file.skipReason) {
            File.SkipReason.Unavailable -> TextPart.UnavailableEmoji()
            File.SkipReason.FileType, File.SkipReason.FileSize -> ""
            else -> file.relativePath
        }
    }

    private suspend fun messageCustomEmojiReady(process: AbstractMessagesProcess, index: Int, message: Message) {
        for (part in message.text) {
            if (part.type == TextPart.Type.CustomEmoji) part.additional = getCustomEmoji(process, index, message, part.additional)
        }
        for (reaction in message.reactions) {
            if (reaction.type == Reaction.Type.CustomEmoji) {
                reaction.documentId = getCustomEmoji(process, index, message, reaction.documentId)
            }
        }
        val rich = message.richMessage ?: return
        val texts = ArrayList<RichText>()
        VisitRichMessage(rich, RichVisitor({ if (it.type == RichText.Type.CustomEmoji) texts.add(it) }, {}, {}))
        for (text in texts) text.customEmojiData = getCustomEmoji(process, index, message, text.customEmojiData)
    }

    private fun buildMessageFileWork(message: Message): List<MessageFileWork> {
        val work = ArrayList<MessageFileWork>()
        var ordinaryMain: File? = null
        var ordinaryDocument: Document? = null
        var ordinaryType = MediaSettings.Type.Photo
        when (val action = message.action.content) {
            is ActionChatEditPhoto -> ordinaryMain = action.photo.image.file
            is ActionSuggestProfilePhoto -> ordinaryMain = action.photo.image.file
            else -> Unit
        }
        when (val content = message.media.content) {
            is Photo -> if (ordinaryMain == null) ordinaryMain = content.image.file
            is Document -> {
                ordinaryDocument = content
                if (ordinaryMain == null) {
                    ordinaryMain = content.file
                    ordinaryType = DocumentMediaType(content)
                }
            }
            is SharedContact -> if (ordinaryMain == null) ordinaryMain = content.vcard
            else -> Unit
        }
        val ordinarySize = ordinaryMain?.size ?: 0
        if (ordinaryMain != null) work.add(MessageFileWork(ordinaryMain, ordinarySize, ordinaryType, false))
        if (ordinaryDocument != null && ordinaryDocument.thumb.width > 0) {
            work.add(MessageFileWork(ordinaryDocument.thumb.file, ordinarySize, DocumentMediaType(ordinaryDocument), false))
        }
        val rich = message.richMessage
        if (rich != null) {
            val richFiles = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<File, Boolean>())
            fun appendRich(file: File, type: Int, controllingSize: Long) {
                if (richFiles.add(file)) work.add(MessageFileWork(file, controllingSize, type, true))
            }
            VisitRichMessage(
                rich,
                RichVisitor(
                    {},
                    { photo -> appendRich(photo.image.file, MediaSettings.Type.Photo, photo.image.file.size) },
                    { document ->
                        val type = DocumentMediaType(document)
                        val controllingSize = document.file.size
                        appendRich(document.file, type, controllingSize)
                        if (document.thumb.width > 0) appendRich(document.thumb.file, type, controllingSize)
                    },
                ),
            )
        }
        return work
    }

    private fun fileOrigin(process: AbstractMessagesProcess, message: Message): FileOrigin {
        val result = FileOrigin()
        result.messageId = message.id
        if (process is TopicProcess) {
            result.peer = process.inputPeer
        } else {
            val p = process as ChatProcess
            val splitIndex = p.info.splits[p.localSplitIndex]
            result.split = if (splitIndex >= 0) splitIndex else splits.size + splitIndex
            result.peer = if (splitIndex >= 0) p.info.input else p.info.migratedFromInput
        }
        return result
    }

    private suspend fun loadMessageFiles(process: AbstractMessagesProcess) {
        val list = process.slice!!.list
        while (process.fileIndex < list.size) {
            val index = process.fileIndex
            val message = list[index]
            if (SkipMessageByDate(message, settings)) {
                ++process.fileIndex
                continue
            }
            messageCustomEmojiReady(process, index, message)
            for (work in buildMessageFileWork(message)) {
                val origin = fileOrigin(process, message)
                origin.richMessage = work.rich
                processFileLoad(work.file, origin, { loadFileProgress(process, index, it) }, FilePolicy(message, work.type, work.controllingSize))
                check(work.file.relativePath.isNotEmpty() || work.file.skipReason != File.SkipReason.None)
            }
            ++process.fileIndex
        }
    }

    private fun loadFileProgress(process: AbstractMessagesProcess, index: Int, progress: FileProgress): Boolean =
        process.fileProgress(
            DownloadProgress(
                randomId = currentFileRandomId,
                path = currentFileRelativePath,
                itemIndex = index,
                ready = progress.ready,
                total = progress.total,
            ),
        )

    /** Returns true to continue with the next slice. */
    private fun finishMessagesSlice(process: ChatProcess): Boolean {
        var slice = process.slice!!
        process.slice = null
        if (slice.list.isNotEmpty()) {
            process.largestIdPlusOne = slice.list.last().id + 1
            val splitIndex = process.info.splits[process.localSplitIndex]
            if (splitIndex < 0) slice = AdjustMigrateMessageIds(slice)
            if (!process.handleSlice(slice)) throw ExportStopped()
        }
        if (process.lastSlice && ++process.localSplitIndex < process.info.splits.size) {
            process.lastSlice = false
            process.largestIdPlusOne = 1
        }
        return !process.lastSlice
    }

    // ---- topic --------------------------------------------------------------

    suspend fun requestTopicMessages(
        peerId: PeerId,
        inputPeer: MTPInputPeer,
        topicRootId: Int,
        start: (Int) -> Boolean,
        progress: (DownloadProgress) -> Boolean,
        slice: (MessagesSlice) -> Boolean,
    ) = rethrow {
        val process = TopicProcess()
        process.context.selfPeerId = Peer_.fromUser(selfId!!)
        process.peerId = peerId
        process.inputPeer = inputPeer
        process.topicRootId = topicRootId
        process.relativePath = "chats/chat_" + java.lang.Long.toUnsignedString(peerId) + "/topic_" + topicRootId + "/"
        process.fileProgress = progress
        process.handleSlice = slice
        topicProcess = process
        try {
            val channel = inputPeer as MTPDinputPeerChannel
            val rootResult = mainRequest(
                MTPchannels_GetMessages(MTPDinputChannel(channel.vchannel_id, channel.vaccess_hash), listOf(MTPDinputMessageID(topicRootId))),
            )
            val rootSlice = when (rootResult) {
                is MTPDmessages_messagesNotModified -> MessagesSlice()
                is MTPDmessages_messages -> ParseMessagesSlice(process.context, rootResult.vmessages, rootResult.vusers, rootResult.vchats, process.relativePath)
                is MTPDmessages_messagesSlice -> ParseMessagesSlice(process.context, rootResult.vmessages, rootResult.vusers, rootResult.vchats, process.relativePath)
                is MTPDmessages_channelMessages -> ParseMessagesSlice(process.context, rootResult.vmessages, rootResult.vusers, rootResult.vchats, process.relativePath)
            }
            val result = requestTopicReplies(process, 0, 0, kMessagesSliceLimit)
            val count = countOf(result)
            if (count < 0) error("Unexpected messagesNotModified received.")
            process.totalCount = count
            if (!start(count)) throw ExportStopped()
            if (rootSlice.list.isNotEmpty()) {
                startMessagesSlice(process, rootSlice)
            } else {
                requestTopicMessagesSlice(process)
            }
            while (finishTopicMessagesSlice(process)) requestTopicMessagesSlice(process)
        } finally {
            topicProcess = null
        }
    }

    private suspend fun requestTopicMessagesSlice(process: TopicProcess) {
        val offsetId = if (process.offsetId == 0) 1 else process.offsetId + 1
        val result = requestTopicReplies(process, offsetId, -kMessagesSliceLimit, kMessagesSliceLimit)
        val (messages, users, chats) = when (result) {
            is MTPDmessages_messagesNotModified -> error("Unexpected messagesNotModified received.")
            is MTPDmessages_messages -> {
                process.lastSlice = true
                Triple(result.vmessages, result.vusers, result.vchats)
            }
            is MTPDmessages_messagesSlice -> Triple(result.vmessages, result.vusers, result.vchats)
            is MTPDmessages_channelMessages -> Triple(result.vmessages, result.vusers, result.vchats)
        }
        val slice = ParseMessagesSlice(process.context, messages, users, chats, process.relativePath)
        if (slice.list.isEmpty()) process.lastSlice = true
        startMessagesSlice(process, slice)
    }

    private suspend fun requestTopicReplies(process: TopicProcess, offsetId: Int, addOffset: Int, limit: Int): MTPmessages_Messages =
        mainRequest(MTPmessages_GetReplies(process.inputPeer, process.topicRootId, offsetId, 0, addOffset, limit, 0, 0, 0))

    private fun finishTopicMessagesSlice(process: TopicProcess): Boolean {
        val slice = process.slice!!
        process.slice = null
        if (slice.list.isNotEmpty()) {
            process.offsetId = slice.list.last().id
            process.processedCount += slice.list.size
            if (!process.handleSlice(slice)) throw ExportStopped()
        }
        val reachedTotal = process.totalCount > 0 && process.processedCount >= process.totalCount
        return !process.lastSlice && !reachedTotal
    }

    // ---- finish -------------------------------------------------------------

    suspend fun finishExport() {
        try {
            rethrow { mainRequest(MTPaccount_FinishTakeoutSession(is_success = true)) }
        } finally {
            takeoutId = null
        }
    }

    suspend fun cancelExportFast() {
        val id = takeoutId ?: return
        runCatching { client.invoke(InvokeWithTakeout(id, MTPaccount_FinishTakeoutSession(is_success = false))) }
    }

    fun skipFile(randomId: Long) {
        if (currentFileRandomId != randomId || randomId == 0L) return
        log("Export Info: File skipped.")
        currentFileSkipped = true
        currentFileJob?.cancel()
    }

    // ---- files --------------------------------------------------------------

    @Volatile
    private var currentFileRelativePath = ""

    private suspend fun processFileLoad(file: File, origin: FileOrigin, progress: (FileProgress) -> Boolean, policy: FilePolicy) {
        val SR = File.SkipReason.None.javaClass
        @Suppress("UNUSED_VARIABLE") val unused = SR
        if (file.relativePath.isNotEmpty() || file.skipReason != File.SkipReason.None) return
        if (SkipMessageByDate(policy.message, settings)) {
            file.skipReason = File.SkipReason.DateLimits
            return
        }
        if (!file.location.isValid && file.content.isEmpty()) {
            file.skipReason = File.SkipReason.Unavailable
            return
        }
        if ((settings.media.types and policy.type) != policy.type) {
            file.skipReason = File.SkipReason.FileType
            return
        }
        if (policy.controllingSize > settings.media.sizeLimit) {
            file.skipReason = File.SkipReason.FileSize
            return
        }
        if (writePreloadedFile(file, origin)) {
            return
        }
        val relativePath = loadFile(file, origin, progress)
        file.relativePath = relativePath
        if (relativePath.isEmpty()) file.skipReason = File.SkipReason.Unavailable
    }

    private class FileProcess(path: String, stats: Stats?) {
        val file = org.tgbackup.export.output.File(path, stats)
        var relativePath = ""
        var randomId = 0L
        var location = FileLocation()
        lateinit var origin: FileOrigin
        var offset = 0L
        var size = 0L
    }

    private fun prepareFileProcess(file: File, origin: FileOrigin): FileProcess {
        val relativePath = org.tgbackup.export.output.File.PrepareRelativePath(settings.path, file.suggestedPath)
        val result = FileProcess(settings.path + relativePath, stats)
        result.relativePath = relativePath
        result.location = file.location.copy()
        result.size = file.size
        result.origin = origin
        result.randomId = java.security.SecureRandom().nextLong()
        return result
    }

    /** Returns true if handled without downloading (file.relativePath may stay empty only on io error). */
    private fun writePreloadedFile(file: File, origin: FileOrigin): Boolean {
        val cached = fileCache.find(file.location)
        if (cached != null) {
            file.relativePath = cached
            return true
        }
        if (file.content.isNotEmpty()) {
            val process = prepareFileProcess(file, origin)
            val result = process.file.writeBlock(file.content)
            process.file.close()
            if (result.isSuccess()) {
                file.relativePath = process.relativePath
                fileCache.save(file.location, file.relativePath)
            } else {
                throw ExportIoError(result)
            }
            return true
        }
        return false
    }

    /** Downloads the file; returns its relative path or "" if unavailable / skipped. */
    private suspend fun loadFile(file: File, origin: FileOrigin, progress: (FileProgress) -> Boolean): String {
        check(file.location.dcId != 0 || file.location.data === MTPDinputTakeoutFileLocation)
        val process = prepareFileProcess(file, origin)
        currentFileRandomId = process.randomId
        currentFileRelativePath = process.relativePath
        currentFileSkipped = false
        try {
            if (!progress(FileProgress(process.file.size(), process.size))) throw ExportStopped()
            return coroutineScope {
                val job = async { downloadParts(process, progress) }
                currentFileJob = job
                try {
                    job.await()
                } catch (e: CancellationException) {
                    if (currentFileSkipped) "" else throw e
                }
            }
        } finally {
            process.file.close()
            currentFileJob = null
            currentFileRandomId = 0
            currentFileRelativePath = ""
        }
    }

    /**
     * loadFilePart / filePartDone: tdesktop keeps a single upload.getFile in
     * flight at a time (requestId guard), so parts are fetched one by one.
     */
    private suspend fun downloadParts(process: FileProcess, progress: (FileProgress) -> Boolean): String {
        while (true) {
            if (process.size > 0 && process.offset >= process.size) break
            val offset = process.offset
            process.offset += kFileChunkSize
            val result = filePart(process, offset) ?: return ""
            val data = when (result) {
                is MTPDupload_fileCdnRedirect -> error("Cdn redirect is not supported.")
                is MTPDupload_file -> result
            }
            if (data.vbytes.isEmpty()) {
                if (process.size > 0) error("Empty bytes received in file part.")
                val r = process.file.writeBlock(ByteArray(0))
                if (!r.isSuccess()) throw ExportIoError(r)
                break
            }
            val r = process.file.writeBlock(data.vbytes)
            if (!r.isSuccess()) throw ExportIoError(r)
            progress(FileProgress(process.file.size(), process.size))
            if (process.size == 0L || process.size > process.offset) continue
            break
        }
        fileCache.save(process.location, process.relativePath)
        return process.relativePath
    }

    /** One upload.getFile with tdesktop's error handling; null = unavailable. */
    private suspend fun filePart(process: FileProcess, offset: Long): MTPupload_File? {
        while (true) {
            try {
                val location = process.location
                return client.invoke(
                    InvokeWithTakeout(takeoutId!!, MTPupload_GetFile(vlocation = location.data!!, voffset = offset, vlimit = kFileChunkSize)),
                    dc = location.dcId,
                    kind = MtpClient.Kind.Media,
                )
            } catch (e: RpcError) {
                when {
                    e.type == "LOCATION_INVALID" || e.type == "VERSION_INVALID" || e.type == "LOCATION_NOT_AVAILABLE" -> {
                        log("Export Error: File unavailable.")
                        return null
                    }
                    e.code == 400 && e.type.startsWith("FILE_REFERENCE_") -> {
                        val refreshed = filePartRefreshReference(process) ?: run {
                            log("Export Error: File unavailable.")
                            return null
                        }
                        process.location = refreshed
                        // filePartRetryReference: one retry with the new reference.
                        try {
                            return client.invoke(
                                InvokeWithTakeout(takeoutId!!, MTPupload_GetFile(vlocation = refreshed.data!!, voffset = offset, vlimit = kFileChunkSize)),
                                dc = refreshed.dcId,
                                kind = MtpClient.Kind.Media,
                            )
                        } catch (e2: RpcError) {
                            if (e2.type == "LOCATION_INVALID" || e2.type == "VERSION_INVALID" || e2.type == "LOCATION_NOT_AVAILABLE") return null
                            if (e2.code == 400 && e2.type.startsWith("FILE_REFERENCE_")) continue
                            throw apiError(e2)
                        }
                    }
                    else -> throw apiError(e)
                }
            }
        }
    }

    private fun filePartMediaFolder(): String =
        chatProcess?.info?.relativePath ?: topicProcess?.relativePath ?: ""

    private suspend fun filePartRefreshReference(process: FileProcess): FileLocation? {
        val origin = process.origin
        if (origin.customEmojiId != 0L) {
            val folder = filePartMediaFolder()
            val result = try {
                mainRequest(MTPmessages_GetCustomEmojiDocuments(listOf(origin.customEmojiId)))
            } catch (e: RpcError) {
                return null
            }
            val context = ParseMediaContext(selfPeerId = Peer_.fromUser(selfId!!))
            var document: Document? = null
            var matches = 0
            for (entry in result) {
                val parsed = ParseDocument(context, entry, folder, 0)
                if (parsed.id != origin.customEmojiId) continue
                ++matches
                if (matches == 1) document = parsed
            }
            if (matches != 1 || document == null) return null
            val refreshed = process.location.copy()
            val mainRefreshed = RefreshFileReference(refreshed, document.file.location)
            val thumbRefreshed = !mainRefreshed && document.thumb.width > 0 &&
                RefreshFileReference(refreshed, document.thumb.file.location)
            return if (mainRefreshed || thumbRefreshed) refreshed else null
        }
        if (origin.richMessage) {
            if (origin.messageId == 0) return null
            val folder = filePartMediaFolder()
            val result = try {
                mainRequest(MTPmessages_GetRichMessage(origin.peer, origin.messageId))
            } catch (e: RpcError) {
                return null
            }
            val richMessage = ExtractFullRichMessage(result, origin.messageId) ?: return null
            val context = ParseMediaContext(selfPeerId = Peer_.fromUser(selfId!!))
            val parsed = ParseRichMessage(context, richMessage, folder, 0)
            if (parsed.part) return null
            return RefreshRichMessageFileReference(process.location, parsed)
        }
        if (origin.messageId == 0) error("FILE_REFERENCE error for non-message file.")
        val peer = origin.peer
        val result = try {
            if (peer is MTPDinputPeerChannel || peer is MTPDinputPeerChannelFromMessage) {
                val channel: MTPInputChannel = when (peer) {
                    is MTPDinputPeerChannel -> MTPDinputChannel(peer.vchannel_id, peer.vaccess_hash)
                    is MTPDinputPeerChannelFromMessage -> MTPDinputChannelFromMessage(peer.vpeer, peer.vmsg_id, peer.vchannel_id)
                    else -> throw IllegalStateException()
                }
                mainRequest(MTPchannels_GetMessages(channel, listOf(MTPDinputMessageID(origin.messageId))))
            } else {
                splitRequest(origin.split, MTPmessages_GetMessages(listOf(MTPDinputMessageID(origin.messageId))))
            }
        } catch (e: RpcError) {
            return null
        }
        val folder = filePartMediaFolder()
        val (messages, users, chats) = when (result) {
            is MTPDmessages_messagesNotModified -> error("Unexpected messagesNotModified received.")
            is MTPDmessages_messages -> Triple(result.vmessages, result.vusers, result.vchats)
            is MTPDmessages_messagesSlice -> Triple(result.vmessages, result.vusers, result.vchats)
            is MTPDmessages_channelMessages -> Triple(result.vmessages, result.vusers, result.vchats)
        }
        val context = ParseMediaContext(selfPeerId = Peer_.fromUser(selfId!!))
        val parsed = ParseMessagesSlice(context, messages, users, chats, folder)
        for (message in parsed.list) {
            if (message.id == origin.messageId) {
                val refreshed = process.location.copy()
                if (RefreshFileReference(refreshed, message.file().location) ||
                    RefreshFileReference(refreshed, message.thumb().file.location)
                ) return refreshed
            }
        }
        return null
    }
}

class ExportIoError(val result: org.tgbackup.export.output.Result) : Exception("IO error at ${result.path}")
