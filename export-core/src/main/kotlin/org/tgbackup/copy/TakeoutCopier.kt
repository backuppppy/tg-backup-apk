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
class CopyPeer(val bareId: Long, val title: String, val input: MTPInputPeer, val isNoForwards: Boolean = false) {
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

    /** When true, a file not found by search is looked for by scanning the account's own channels' media by id. */
    var deepScan: Boolean = false

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
                    is MTPDchannel -> CopyPeer(c.vid, c.vtitle, MTPDinputPeerChannel(c.vid, c.vaccess_hash ?: 0), c.is_noforwards)
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
        // In a channel protected from forwarding, takeout getHistory returns only the account's own posts
        // (or nothing); an empty-query search through takeout returns the whole history.
        val r = takeout(search(peer, offsetId = afterId + 1, addOffset = -limit, limit = limit, minId = afterId))
        return messagesOf(r).filter { idOf(it) > afterId }.sortedBy { idOf(it) }
    }

    /** The newest message id in [peer], or 0 for an empty chat. */
    suspend fun latestId(peer: CopyPeer): Int =
        messagesOf(takeout(search(peer, offsetId = 0, addOffset = 0, limit = 1, minId = 0))).maxOfOrNull { idOf(it) } ?: 0

    private fun search(peer: CopyPeer, offsetId: Int, addOffset: Int, limit: Int, minId: Int) = MTPmessages_Search(
        vpeer = peer.input,
        vq = "",
        vfilter = MTPDinputMessagesFilterEmpty,
        vmin_date = 0,
        vmax_date = 0,
        voffset_id = offsetId,
        vadd_offset = addOffset,
        vlimit = limit,
        vmax_id = 0,
        vmin_id = minId,
        vhash = 0,
    )

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

    /** Telegram refused the file by reference and no unprotected copy of it could be used. */
    class ForwardsRestricted(
        val reason: String,
        val details: Map<String, String>,
        cause: RpcError,
    ) : Exception("CHAT_FORWARDS_RESTRICTED: $reason", cause)

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
                        val why = mediaDetails(current)
                        val publicMedia = findPublicRef(current, target, why)
                            ?: throw ForwardsRestricted(why.remove("reason") ?: "not-found", why, e)
                        val r2 = try {
                            client.invoke(
                                MTPmessages_SendMedia(
                                    vpeer = target.input,
                                    vmedia = inputMedia(publicMedia)!!,
                                    vmessage = text,
                                    vrandom_id = Random.nextLong(),
                                    ventities = entities,
                                    is_invert_media = current.is_invert_media,
                                ),
                            )
                        } catch (e2: RpcError) {
                            if (e2.type != "CHAT_FORWARDS_RESTRICTED") throw e2
                            throw ForwardsRestricted("public-copy-restricted", why, e2)
                        }
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

    private fun mediaDetails(m: MTPDmessage): MutableMap<String, String> {
        val d = mutableMapOf(
            "kind" to kindOf(m).name,
            "media" to (m.vmedia?.javaClass?.simpleName?.removePrefix("MTPDmessageMedia") ?: "none"),
            "msg_noforwards" to m.is_noforwards.toString(),
            "msg_out" to m.is_out.toString(),
            "msg_forwarded" to (m.vfwd_from != null).toString(),
            "msg_date" to m.vdate.toString(),
        )
        when (val media = m.vmedia) {
            is MTPDmessageMediaDocument -> (media.vdocument as? MTPDdocument)?.let { doc ->
                d["doc_id"] = doc.vid.toString()
                d["doc_date"] = doc.vdate.toString()
                d["doc_dc"] = doc.vdc_id.toString()
                d["mime"] = doc.vmime_type
                d["size"] = doc.vsize.toString()
                doc.vattributes.filterIsInstance<MTPDdocumentAttributeFilename>().firstOrNull()?.let { d["file_name"] = it.vfile_name }
            }
            is MTPDmessageMediaPhoto -> (media.vphoto as? MTPDphoto)?.let { p ->
                d["photo_id"] = p.vid.toString()
                d["photo_date"] = p.vdate.toString()
                d["photo_dc"] = p.vdc_id.toString()
            }
            else -> {}
        }
        return d
    }

    /**
     * Searches for the same document where it is not protected: first in [target] itself, then
     * globally. Returns the message media from there (with a usable file_reference), or null;
     * then [why] holds a "reason" code and what the searches saw.
     * Called when sendMedia fails with CHAT_FORWARDS_RESTRICTED.
     */
    /** The global id of the file inside [media]: document id or photo id. Same file → same id anywhere. */
    private fun fileIdOf(media: MTPMessageMedia?): Long? = when (media) {
        is MTPDmessageMediaDocument -> (media.vdocument as? MTPDdocument)?.vid
        is MTPDmessageMediaPhoto -> (media.vphoto as? MTPDphoto)?.vid
        else -> null
    }

    /** Text queries to locate the same file by: file name (raw, de-underscored, no extension), audio title, caption. */
    private fun searchQueries(msg: MTPDmessage): List<String> {
        val out = LinkedHashSet<String>()
        fun add(s: String?) { s?.trim()?.take(64)?.let { if (it.length >= 3) out += it } }
        val doc = (msg.vmedia as? MTPDmessageMediaDocument)?.vdocument as? MTPDdocument
        doc?.vattributes?.filterIsInstance<MTPDdocumentAttributeFilename>()?.firstOrNull()?.vfile_name?.let { fn ->
            val base = fn.substringBeforeLast('.')
            add(base)
            add(base.replace('_', ' ').replace('.', ' ').replace(Regex("\\s+"), " "))
            add(fn)
        }
        doc?.vattributes?.filterIsInstance<MTPDdocumentAttributeAudio>()?.firstOrNull()?.let { a ->
            add(a.vtitle)
            if (a.vperformer != null && a.vtitle != null) add("${a.vperformer} ${a.vtitle}")
        }
        add(msg.vmessage.lineSequence().firstOrNull { it.isNotBlank() })
        return out.toList()
    }

    private fun chatsOf(r: MTPmessages_Messages): List<MTPChat> = when (r) {
        is MTPDmessages_messages -> r.vchats
        is MTPDmessages_messagesSlice -> r.vchats
        is MTPDmessages_channelMessages -> r.vchats
        else -> emptyList()
    }

    private var ownPeersCache: List<CopyPeer>? = null

    /** Every unprotected channel/group the account is a member of (fetched once, cached). */
    private suspend fun ownUnprotectedPeers(): List<CopyPeer> {
        ownPeersCache?.let { return it }
        val out = LinkedHashMap<Long, CopyPeer>()
        for (folder in listOf(0, 1)) {
            var offsetDate = 0
            var offsetId = 0
            var offsetPeer: MTPInputPeer = MTPDinputPeerEmpty
            while (true) {
                val d = try {
                    client.invoke(
                        MTPmessages_GetDialogs(
                            vfolder_id = folder, voffset_date = offsetDate, voffset_id = offsetId,
                            voffset_peer = offsetPeer, vlimit = 100, vhash = 0,
                        ),
                    )
                } catch (e: RpcError) { break }
                val (dialogs, messages, chats, users) = when (d) {
                    is MTPDmessages_dialogs -> Quad(d.vdialogs, d.vmessages, d.vchats, d.vusers)
                    is MTPDmessages_dialogsSlice -> Quad(d.vdialogs, d.vmessages, d.vchats, d.vusers)
                    else -> break
                }
                for (c in chats.filterIsInstance<MTPDchannel>()) {
                    if (!c.is_noforwards && !c.is_left) {
                        out.getOrPut(c.vid) { CopyPeer(c.vid, c.vtitle, MTPDinputPeerChannel(c.vid, c.vaccess_hash ?: 0), false) }
                    }
                }
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
                    is MTPDpeerChannel -> MTPDinputPeerChannel(p.vchannel_id, chats.filterIsInstance<MTPDchannel>().find { it.vid == p.vchannel_id }?.vaccess_hash ?: 0)
                    is MTPDpeerChat -> MTPDinputPeerChat(p.vchat_id)
                    is MTPDpeerUser -> MTPDinputPeerUser(p.vuser_id, users.filterIsInstance<MTPDuser>().find { it.vid == p.vuser_id }?.vaccess_hash ?: 0)
                    else -> break
                }
                if (dialogs.isEmpty()) break
            }
        }
        return out.values.toList().also { ownPeersCache = it }
    }

    /**
     * Deep fallback: scans the media of every unprotected channel the account is in, by empty query, matching
     * by file id. Finds a copy whose text global search could not match. Bounded by [maxChannels]/[maxPages].
     */
    private suspend fun deepScanOwnDialogs(
        wantId: Long,
        filter: MTPMessagesFilter,
        skipBareId: Long,
        why: MutableMap<String, String>,
        maxChannels: Int = 60,
        maxPages: Int = 4,
    ): MTPMessageMedia? {
        val peers = ownUnprotectedPeers().filter { it.bareId != skipBareId }.take(maxChannels)
        why["deep_scan_channels"] = peers.size.toString()
        var scanned = 0
        for (peer in peers) {
            var offId = 0
            for (page in 1..maxPages) {
                val r = try {
                    client.invoke(
                        MTPmessages_Search(
                            vpeer = peer.input, vq = "", vfilter = filter,
                            vmin_date = 0, vmax_date = 0, voffset_id = offId, vadd_offset = 0, vlimit = 100,
                            vmax_id = 0, vmin_id = 0, vhash = 0,
                        ),
                    )
                } catch (e: RpcError) { break }
                val list = messagesOf(r).filterIsInstance<MTPDmessage>()
                scanned += list.size
                list.firstOrNull { fileIdOf(it.vmedia) == wantId }?.let {
                    log("deep scan found file $wantId in '${peer.title}'")
                    why["public_copy"] = "deep: ${peer.title} (${peer.bareId})"
                    why["deep_scan_messages"] = scanned.toString()
                    return it.vmedia
                }
                val last = list.lastOrNull() ?: break
                if (list.size < 100) break
                offId = last.vid
            }
        }
        why["deep_scan_messages"] = scanned.toString()
        return null
    }

    /**
     * Finds an unprotected copy of the SAME file (matched exactly by document/photo id, not by name) so it can
     * be sent by reference without downloading. Candidates are gathered by text search — in the target channel
     * first, then globally across several pages, trying multiple queries — but the final match is always by file id.
     * Returns the media to send, or null; then [why] holds a "reason" code and what the searches saw.
     */
    private suspend fun findPublicRef(msg: MTPDmessage, target: CopyPeer, why: MutableMap<String, String>): MTPMessageMedia? {
        val wantId = fileIdOf(msg.vmedia) ?: return null.also { why["reason"] = "no-file-id" }
        val filter: MTPMessagesFilter = if (msg.vmedia is MTPDmessageMediaPhoto) MTPDinputMessagesFilterPhotos else MTPDinputMessagesFilterDocument
        val queries = searchQueries(msg)
        why["search_queries"] = queries.joinToString(" | ")
        fun matches(m: MTPDmessage) = fileIdOf(m.vmedia) == wantId

        // 1a. Scan the target's own media (empty query) by id. Catches re-runs and media with no
        // searchable text (e.g. a photo with no caption), which global search can never find.
        if (!target.isNoForwards) {
            try {
                var offId = 0
                for (page in 1..GLOBAL_SEARCH_PAGES) {
                    val r = client.invoke(
                        MTPmessages_Search(
                            vpeer = target.input, vq = "", vfilter = filter,
                            vmin_date = 0, vmax_date = 0, voffset_id = offId, vadd_offset = 0, vlimit = 100,
                            vmax_id = 0, vmin_id = 0, vhash = 0,
                        ),
                    )
                    val list = messagesOf(r).filterIsInstance<MTPDmessage>()
                    list.firstOrNull { matches(it) }?.let {
                        log("found ref for file $wantId in the target channel (id scan)")
                        why["public_copy"] = "target ${target.title} (${target.bareId})"
                        why["matched_query"] = "(target id scan)"
                        return it.vmedia
                    }
                    val last = list.lastOrNull() ?: break
                    if (list.size < 100) break
                    offId = last.vid
                }
            } catch (e: RpcError) {
                why["target_scan_error"] = "${e.code} ${e.type}"
            }
        }
        if (queries.isEmpty()) return null.also { why["reason"] = "no-query" }

        // 1b. The target channel often already holds the file from an earlier copy, and global search can miss it.
        if (!target.isNoForwards) {
            for (q in queries) {
                try {
                    val r = client.invoke(
                        MTPmessages_Search(
                            vpeer = target.input, vq = q, vfilter = filter,
                            vmin_date = 0, vmax_date = 0, voffset_id = 0, vadd_offset = 0, vlimit = 50,
                            vmax_id = 0, vmin_id = 0, vhash = 0,
                        ),
                    )
                    messagesOf(r).filterIsInstance<MTPDmessage>().firstOrNull { matches(it) }?.let {
                        log("found ref for file $wantId in the target channel (query '$q')")
                        why["public_copy"] = "target ${target.title} (${target.bareId})"
                        why["matched_query"] = q
                        return it.vmedia
                    }
                } catch (e: RpcError) {
                    why["target_search_error"] = "${e.code} ${e.type}"
                }
            }
        }

        // 2. Global search, one pass per query, several pages each.
        val protectedHits = mutableListOf<String>()
        var totalResults = 0
        for (q in queries) {
            var offRate = 0
            var offPeer: MTPInputPeer = MTPDinputPeerEmpty
            var offId = 0
            for (page in 1..GLOBAL_SEARCH_PAGES) {
                val r = try {
                    client.invoke(
                        MTPmessages_SearchGlobal(
                            vq = q, vfilter = filter, vmin_date = 0, vmax_date = 0,
                            voffset_rate = offRate, voffset_peer = offPeer, voffset_id = offId, vlimit = 50,
                        ),
                    )
                } catch (e: RpcError) {
                    why["search_error"] = "${e.code} ${e.type}"
                    break
                }
                val msgs = messagesOf(r)
                val chats = chatsOf(r)
                totalResults += msgs.size
                why["search_results"] = totalResults.toString()
                for (m in msgs.filterIsInstance<MTPDmessage>()) {
                    if (!matches(m)) continue
                    when (val peer = m.vpeer_id) {
                        is MTPDpeerUser, is MTPDpeerChat -> {
                            log("found ref for file $wantId in private/group context (query '$q')")
                            why["public_copy"] = "private chat / basic group"
                            why["matched_query"] = q
                            return m.vmedia
                        }
                        is MTPDpeerChannel -> {
                            val ch = chats.filterIsInstance<MTPDchannel>().find { it.vid == peer.vchannel_id } ?: continue
                            if (ch.is_noforwards) {
                                protectedHits += "${ch.vtitle} (${ch.vid})"
                                continue
                            }
                            log("found ref for file $wantId in channel '${ch.vtitle}' (query '$q')")
                            why["public_copy"] = "${ch.vtitle} (${ch.vid})"
                            why["matched_query"] = q
                            return m.vmedia
                        }
                        else -> continue
                    }
                }
                val nextRate = (r as? MTPDmessages_messagesSlice)?.vnext_rate
                val last = msgs.lastOrNull() as? MTPDmessage
                if (nextRate == null || last == null || msgs.size < 50) break
                offRate = nextRate
                offId = last.vid
                offPeer = when (val p = last.vpeer_id) {
                    is MTPDpeerChannel -> MTPDinputPeerChannel(
                        p.vchannel_id,
                        chats.filterIsInstance<MTPDchannel>().find { it.vid == p.vchannel_id }?.vaccess_hash ?: 0,
                    )
                    is MTPDpeerChat -> MTPDinputPeerChat(p.vchat_id)
                    else -> break
                }
            }
        }
        // 3. Deep fallback: scan the account's own unprotected channels' media by id (no text).
        if (deepScan) {
            deepScanOwnDialogs(wantId, filter, skipBareId = target.bareId, why = why)?.let { return it }
        }

        if (protectedHits.isNotEmpty()) {
            why["protected_copies"] = protectedHits.distinct().joinToString()
            why["reason"] = "only-in-protected-chats"
        } else {
            why["reason"] = "no-public-copy-found"
        }
        return null
    }

    companion object {
        const val MAX_FILE_SIZE = 4000L * 1024 * 1024
        const val GLOBAL_SEARCH_PAGES = 4

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
