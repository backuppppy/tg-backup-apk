package org.tgbackup.copy

import org.tgbackup.mtproto.MtpClient
import org.tgbackup.mtproto.RpcError
import org.tgbackup.mtproto.tl.*
import kotlin.random.Random

/** What a source message holds, for the media-type filter. */
enum class CopyKind { TEXT, PHOTO, VIDEO, DOCUMENT, AUDIO, VOICE, ROUND, ANIMATION, STICKER, OTHER }

/** A message ready to be sent again: its media is referenced by id, nothing is downloaded. */
class SourceMessage(val raw: MTPDmessage) {
    val id: Int get() = raw.vid
    val kind: CopyKind = TakeoutCopier.kindOf(raw)
}

/** A channel or basic group the copier can read from or send to. */
class CopyPeer(val bareId: Long, val title: String, val input: MTPInputPeer) {
    val channel: MTPInputChannel?
        get() = (input as? MTPDinputPeerChannel)?.let { MTPDinputChannel(it.vchannel_id, it.vaccess_hash) }
}

/**
 * Copies messages without downloading them.
 *
 * Messages are read inside a takeout session, where Telegram returns the media of
 * protected and copyright-restricted chats with valid file references; those
 * references are then sent with a plain messages.sendMedia from the same session.
 */
class TakeoutCopier(
    private val client: MtpClient,
    private val log: (String) -> Unit = {},
) {
    private var takeoutId: Long? = null

    val hasTakeout: Boolean get() = takeoutId != null

    /** Opens the takeout session. Throws RpcError TAKEOUT_INIT_DELAY_x until the user approves it. */
    suspend fun begin() {
        if (takeoutId != null) return
        val t = client.invoke(
            MTPaccount_InitTakeoutSession(
                is_message_chats = true,
                is_message_megagroups = true,
                is_message_channels = true,
                is_files = true,
                vfile_max_size = MAX_FILE_SIZE,
            ),
        ) as MTPDaccount_takeout
        takeoutId = t.vid
        log("takeout session ${t.vid} opened")
    }

    suspend fun finish() {
        val id = takeoutId ?: return
        takeoutId = null
        runCatching { client.invoke(InvokeWithTakeout(id, MTPaccount_FinishTakeoutSession(is_success = true))) }
    }

    private suspend fun <R> takeout(fn: TlFunction<R>): R {
        begin()
        return try {
            client.invoke(InvokeWithTakeout(takeoutId!!, fn))
        } catch (e: RpcError) {
            if (e.type != "TAKEOUT_INVALID" && e.type != "TAKEOUT_REQUIRED") throw e
            // The server closed the session (e.g. it expired): open a new one once.
            log("takeout session closed by the server (${e.type}), reopening")
            takeoutId = null
            begin()
            client.invoke(InvokeWithTakeout(takeoutId!!, fn))
        }
    }

    /**
     * Finds the chats with the given bare ids (channel id or basic group id, without -100)
     * in the account's dialogs. Ids that are not found are missing from the result.
     */
    suspend fun resolve(bareIds: Collection<Long>): Map<Long, CopyPeer> {
        val wanted = bareIds.toMutableSet()
        val found = HashMap<Long, CopyPeer>()
        fun take(chats: List<MTPChat>) {
            for (c in chats) {
                val peer = when (c) {
                    is MTPDchannel -> CopyPeer(c.vid, c.vtitle, MTPDinputPeerChannel(c.vid, c.vaccess_hash ?: 0))
                    is MTPDchat -> CopyPeer(c.vid, c.vtitle, MTPDinputPeerChat(c.vid))
                    else -> null
                } ?: continue
                if (wanted.remove(peer.bareId)) found[peer.bareId] = peer
            }
        }
        for (folder in listOf(0, 1)) {
            var offsetDate = 0
            var offsetId = 0
            var offsetPeer: MTPInputPeer = MTPDinputPeerEmpty
            while (wanted.isNotEmpty()) {
                val d = client.invoke(
                    MTPmessages_GetDialogs(
                        vfolder_id = folder,
                        voffset_date = offsetDate,
                        voffset_id = offsetId,
                        voffset_peer = offsetPeer,
                        vlimit = 100,
                        vhash = 0,
                    ),
                )
                val (dialogs, messages, chats, users) = when (d) {
                    is MTPDmessages_dialogs -> Quad(d.vdialogs, d.vmessages, d.vchats, d.vusers)
                    is MTPDmessages_dialogsSlice -> Quad(d.vdialogs, d.vmessages, d.vchats, d.vusers)
                    else -> break
                }
                take(chats)
                if (d !is MTPDmessages_dialogsSlice) break
                val last = dialogs.filterIsInstance<MTPDdialog>().lastOrNull() ?: break
                offsetDate = messages.firstNotNullOfOrNull { m ->
                    when {
                        m is MTPDmessage && m.vid == last.vtop_message && m.vpeer_id.same(last.vpeer) -> m.vdate
                        m is MTPDmessageService && m.vid == last.vtop_message && m.vpeer_id.same(last.vpeer) -> m.vdate
                        else -> null
                    }
                } ?: break
                offsetId = last.vtop_message
                offsetPeer = when (val p = last.vpeer) {
                    is MTPDpeerChannel -> MTPDinputPeerChannel(
                        p.vchannel_id,
                        chats.filterIsInstance<MTPDchannel>().find { it.vid == p.vchannel_id }?.vaccess_hash ?: 0,
                    )
                    is MTPDpeerChat -> MTPDinputPeerChat(p.vchat_id)
                    is MTPDpeerUser -> MTPDinputPeerUser(
                        p.vuser_id,
                        users.filterIsInstance<MTPDuser>().find { it.vid == p.vuser_id }?.vaccess_hash ?: 0,
                    )
                    else -> break
                }
                if (dialogs.isEmpty()) break
            }
        }
        return found
    }

    /** Up to [limit] messages with id > [afterId], oldest first, read through the takeout session. */
    suspend fun historyAfter(peer: CopyPeer, afterId: Int, limit: Int = 100): List<MTPMessage> {
        // min_id=max(afterId,1) anchors the lower bound to a concrete value.
        // When afterId=0, using min_id=0 (no bound) causes InvokeWithTakeout to return
        // the NEWEST messages instead of the OLDEST. min_id=1 fixes this.
        val minId = if (afterId == 0) 1 else afterId
        val r = takeout(
            MTPmessages_GetHistory(
                vpeer = peer.input,
                voffset_id = afterId + 1,
                voffset_date = 0,
                vadd_offset = -limit,
                vlimit = limit,
                vmax_id = 0,
                vmin_id = minId,
                vhash = 0,
            ),
        )
        return messagesOf(r).filter { idOf(it) > afterId }.sortedBy { idOf(it) }
    }

    /** The newest message id in [peer], or 0 for an empty chat. */
    suspend fun latestId(peer: CopyPeer): Int {
        val r = takeout(
            MTPmessages_GetHistory(
                vpeer = peer.input, voffset_id = 0, voffset_date = 0, vadd_offset = 0,
                vlimit = 1, vmax_id = 0, vmin_id = 0, vhash = 0,
            ),
        )
        return messagesOf(r).maxOfOrNull { idOf(it) } ?: 0
    }

    /** Reads one message again, e.g. to get a fresh file reference. */
    suspend fun reload(peer: CopyPeer, id: Int): MTPDmessage? {
        val fn: TlFunction<MTPmessages_Messages> = peer.channel?.let { MTPchannels_GetMessages(it, listOf(MTPDinputMessageID(id))) }
            ?: MTPmessages_GetMessages(listOf(MTPDinputMessageID(id)))
        return messagesOf(takeout(fn)).filterIsInstance<MTPDmessage>().firstOrNull { it.vid == id }
    }

    sealed class Sent {
        /** Delivered; [messageId] is the new message in the target (0 if unknown). */
        class Ok(val messageId: Int) : Sent()

        /** Nothing to send (e.g. an empty text after removing links, or an unsupported kind). */
        class Skipped(val why: String) : Sent()
    }

    /**
     * Sends [msg] (read from [source]) to [target]. An expired file reference is refreshed
     * once by reading the message again. Flood waits are waited out by the client.
     */
    suspend fun send(source: CopyPeer, target: CopyPeer, msg: MTPDmessage, stripLinks: Boolean): Sent {
        val media = msg.vmedia
        val keepLink = media is MTPDmessageMediaWebPage
        val strip = stripLinks && !keepLink
        val text = if (strip) LINK_REGEX.replace(msg.vmessage, "").trim() else msg.vmessage
        // Removing links shifts every offset, so formatting entities are dropped together with them.
        val entities = if (strip) null else msg.ventities?.filter { it !is MTPDmessageEntityMentionName }?.ifEmpty { null }

        if (media == null || media is MTPDmessageMediaEmpty || media is MTPDmessageMediaWebPage) {
            if (text.isBlank()) return Sent.Skipped("ריק אחרי ניקוי קישורים")
            val r = client.invoke(
                MTPmessages_SendMessage(
                    vpeer = target.input,
                    vmessage = text,
                    vrandom_id = Random.nextLong(),
                    ventities = entities,
                    is_no_webpage = !keepLink,
                ),
            )
            return Sent.Ok(sentId(r))
        }

        // Paid media (Telegram Stars): the takeout session only returns a preview, not the real media.
        // Copy the text caption if present; otherwise skip.
        if (media is MTPDmessageMediaPaidMedia && inputMedia(media) == null) {
            if (text.isBlank()) return Sent.Skipped("מדיה בתשלום — ללא כיתוב")
            val r = client.invoke(
                MTPmessages_SendMessage(
                    vpeer = target.input,
                    vmessage = text,
                    vrandom_id = Random.nextLong(),
                    ventities = entities,
                    is_no_webpage = true,
                ),
            )
            return Sent.Ok(sentId(r))
        }

        var current = msg
        var refreshed = false
        var triedPublicRef = false
        while (true) {
            val input = inputMedia(current.vmedia) ?: return Sent.Skipped("סוג מדיה שלא נתמך")
            try {
                val r = client.invoke(
                    MTPmessages_SendMedia(
                        vpeer = target.input,
                        vmedia = input,
                        vmessage = text,
                        vrandom_id = Random.nextLong(),
                        ventities = entities,
                        is_invert_media = current.is_invert_media,
                    ),
                )
                return Sent.Ok(sentId(r))
            } catch (e: RpcError) {
                when {
                    !triedPublicRef && e.type == "CHAT_FORWARDS_RESTRICTED" -> {
                        triedPublicRef = true
                        val publicMedia = findPublicRef(current) ?: throw e
                        val r2 = client.invoke(
                            MTPmessages_SendMedia(
                                vpeer = target.input,
                                vmedia = inputMedia(publicMedia)!!,
                                vmessage = text,
                                vrandom_id = Random.nextLong(),
                                ventities = entities,
                                is_invert_media = current.is_invert_media,
                            ),
                        )
                        return Sent.Ok(sentId(r2))
                    }
                    !refreshed && e.type.startsWith("FILE_REFERENCE_") -> {
                        refreshed = true
                        log("file reference of ${current.vid} expired, reading it again")
                        current = reload(source, current.vid) ?: throw e
                    }
                    else -> throw e
                }
            }
        }
    }

    /**
     * Searches for the same document in any accessible public (noforwards=false) channel.
     * Returns the message media from there (with a public file_reference), or null if not found.
     * Called when sendMedia fails with CHAT_FORWARDS_RESTRICTED.
     */
    private suspend fun findPublicRef(msg: MTPDmessage): MTPMessageMedia? {
        val doc = (msg.vmedia as? MTPDmessageMediaDocument)?.vdocument as? MTPDdocument ?: return null
        val filename = doc.vattributes.filterIsInstance<MTPDdocumentAttributeFilename>()
            .firstOrNull()?.vfile_name ?: return null
        val q = filename.substringBeforeLast('.').take(40).trim()
        if (q.length < 3) return null

        val r = try {
            client.invoke(
                MTPmessages_SearchGlobal(
                    vq = q,
                    vfilter = MTPDinputMessagesFilterDocument,
                    vmin_date = 0, vmax_date = 0,
                    voffset_rate = 0, voffset_peer = MTPDinputPeerEmpty, voffset_id = 0,
                    vlimit = 50,
                ),
            )
        } catch (e: RpcError) { return null }

        val (msgs, chats) = when (r) {
            is MTPDmessages_messages -> r.vmessages to r.vchats
            is MTPDmessages_messagesSlice -> r.vmessages to r.vchats
            is MTPDmessages_channelMessages -> r.vmessages to r.vchats
            else -> return null
        }

        for (m in msgs.filterIsInstance<MTPDmessage>()) {
            val d = (m.vmedia as? MTPDmessageMediaDocument)?.vdocument as? MTPDdocument ?: continue
            if (d.vid != doc.vid) continue
            when (val peer = m.vpeer_id) {
                // Private chat or basic group: no noforwards restriction
                is MTPDpeerUser, is MTPDpeerChat -> {
                    log("found public ref for doc ${doc.vid} in private/group context")
                    return m.vmedia
                }
                is MTPDpeerChannel -> {
                    val ch = chats.filterIsInstance<MTPDchannel>().find { it.vid == peer.vchannel_id }
                        ?: continue
                    if (ch.is_noforwards) continue
                    log("found public ref for doc ${doc.vid} in channel '${ch.vtitle}'")
                    return m.vmedia
                }
                else -> continue
            }
        }
        return null
    }

    companion object {
        const val MAX_FILE_SIZE = 4000L * 1024 * 1024

        val LINK_REGEX = Regex("""https?://\S+|www\.\S+|t\.me/\S+|@\S+""")

        fun idOf(m: MTPMessage): Int = when (m) {
            is MTPDmessage -> m.vid
            is MTPDmessageService -> m.vid
            is MTPDmessageEmpty -> m.vid
            else -> 0
        }

        fun messagesOf(r: MTPmessages_Messages): List<MTPMessage> = when (r) {
            is MTPDmessages_messages -> r.vmessages
            is MTPDmessages_messagesSlice -> r.vmessages
            is MTPDmessages_channelMessages -> r.vmessages
            else -> emptyList()
        }

        fun inputMedia(media: MTPMessageMedia?): MTPInputMedia? = when (media) {
            is MTPDmessageMediaPhoto -> (media.vphoto as? MTPDphoto)?.let {
                MTPDinputMediaPhoto(
                    is_spoiler = media.is_spoiler,
                    vid = MTPDinputPhoto(it.vid, it.vaccess_hash, it.vfile_reference),
                )
            }
            is MTPDmessageMediaDocument -> (media.vdocument as? MTPDdocument)?.let {
                MTPDinputMediaDocument(
                    is_spoiler = media.is_spoiler,
                    vid = MTPDinputDocument(it.vid, it.vaccess_hash, it.vfile_reference),
                )
            }
            // Quiz polls need correct_answers which are not in the source message — skip them.
            is MTPDmessageMediaPoll -> (media.vpoll as? MTPDpoll)?.takeIf { !it.is_quiz }?.let { poll ->
                MTPDinputMediaPoll(
                    vpoll = MTPDpoll(
                        vid = 0,
                        vquestion = poll.vquestion,
                        vanswers = poll.vanswers,
                        is_public_voters = poll.is_public_voters,
                        is_multiple_choice = poll.is_multiple_choice,
                        is_shuffle_answers = poll.is_shuffle_answers,
                        is_revoting_disabled = poll.is_revoting_disabled,
                        vhash = 0,
                    ),
                )
            }
            is MTPDmessageMediaDice -> MTPDinputMediaDice(vemoticon = media.vemoticon)
            is MTPDmessageMediaGeo -> (media.vgeo as? MTPDgeoPoint)?.let { geo ->
                MTPDinputMediaGeoPoint(vgeo_point = MTPDinputGeoPoint(vlat = geo.vlat, vlong = geo.vlong))
            }
            is MTPDmessageMediaContact -> MTPDinputMediaContact(
                vphone_number = media.vphone_number,
                vfirst_name = media.vfirst_name,
                vlast_name = media.vlast_name,
                vvcard = media.vvcard,
            )
            // Paid media: extract the first unlocked extended_media item (available in a takeout session).
            is MTPDmessageMediaPaidMedia -> media.vextended_media
                .filterIsInstance<MTPDmessageExtendedMedia>()
                .firstOrNull()
                ?.let { inputMedia(it.vmedia) }
            else -> null
        }

        fun kindOf(m: MTPDmessage): CopyKind = kindOfMedia(m.vmedia)

        private fun kindOfMedia(media: MTPMessageMedia?): CopyKind = when (media) {
            null, is MTPDmessageMediaEmpty, is MTPDmessageMediaWebPage -> CopyKind.TEXT
            // Paid media: determine kind from the first unlocked extended_media item (takeout session).
            is MTPDmessageMediaPaidMedia -> media.vextended_media
                .filterIsInstance<MTPDmessageExtendedMedia>()
                .firstOrNull()
                ?.let { kindOfMedia(it.vmedia) } ?: CopyKind.OTHER
            is MTPDmessageMediaPhoto -> if (media.vphoto is MTPDphoto) CopyKind.PHOTO else CopyKind.OTHER
            is MTPDmessageMediaDocument -> {
                val d = media.vdocument as? MTPDdocument
                val attrs = d?.vattributes.orEmpty()
                val video = attrs.filterIsInstance<MTPDdocumentAttributeVideo>().firstOrNull()
                val audio = attrs.filterIsInstance<MTPDdocumentAttributeAudio>().firstOrNull()
                when {
                    d == null -> CopyKind.OTHER
                    attrs.any { it is MTPDdocumentAttributeSticker } -> CopyKind.STICKER
                    attrs.any { it === MTPDdocumentAttributeAnimated } -> CopyKind.ANIMATION
                    video != null && video.is_round_message -> CopyKind.ROUND
                    video != null -> CopyKind.VIDEO
                    audio != null && audio.is_voice -> CopyKind.VOICE
                    audio != null -> CopyKind.AUDIO
                    else -> CopyKind.DOCUMENT
                }
            }
            else -> CopyKind.OTHER
        }

        /** The id of the new message in a sendMessage / sendMedia result. */
        fun sentId(u: MTPUpdates): Int = when (u) {
            is MTPDupdateShortSentMessage -> u.vid
            is MTPDupdates -> u.vupdates.firstNotNullOfOrNull { up ->
                when (up) {
                    is MTPDupdateNewChannelMessage -> idOf(up.vmessage)
                    is MTPDupdateNewMessage -> idOf(up.vmessage)
                    else -> null
                }
            } ?: u.vupdates.filterIsInstance<MTPDupdateMessageID>().firstOrNull()?.vid ?: 0
            else -> 0
        }

        private fun MTPPeer.same(o: MTPPeer): Boolean = when {
            this is MTPDpeerChannel && o is MTPDpeerChannel -> vchannel_id == o.vchannel_id
            this is MTPDpeerChat && o is MTPDpeerChat -> vchat_id == o.vchat_id
            this is MTPDpeerUser && o is MTPDpeerUser -> vuser_id == o.vuser_id
            else -> false
        }
    }

    private data class Quad(val dialogs: List<MTPDialog>, val messages: List<MTPMessage>, val chats: List<MTPChat>, val users: List<MTPUser>)
}
