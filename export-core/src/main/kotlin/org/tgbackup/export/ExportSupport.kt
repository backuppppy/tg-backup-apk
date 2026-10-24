package org.tgbackup.export

import org.tgbackup.export.data.*
import org.tgbackup.mtproto.MtpClient
import org.tgbackup.mtproto.tl.*

/** A chat that can be exported, as listed from the export session. */
class ChatEntry(
    val input: MTPInputPeer,
    val peerId: PeerId,
    val title: String,
    val username: String,
    val type: DialogInfo.Type,
    val isForum: Boolean,
    val archived: Boolean,
)

class TopicEntry(val id: Int, val title: String)

/** Things Desktop's export takes from the running app rather than the export module. */
object ExportSupport {

    /** Session config: Environment::internalLinksDomain = config.me_url_prefix. */
    suspend fun internalLinksDomain(client: MtpClient): String {
        val cfg = client.invoke(MTPhelp_GetConfig()) as MTPDconfig
        return cfg.vme_url_prefix
    }

    /** Countries::Manager::request: the server phone formats used by FormatPhone. */
    suspend fun loadCountries(client: MtpClient) {
        val result = client.invoke(MTPhelp_GetCountriesList("", 0))
        val data = result as? MTPDhelp_countriesList ?: return
        val infos = ArrayList<CountryInfo>()
        for (country in data.vcountries) {
            val c = country as MTPDhelp_country
            if (c.is_hidden) continue
            val codes = c.vcountry_codes.map { code ->
                val cc = code as MTPDhelp_countryCode
                CallingCodeInfo(
                    cc.vcountry_code,
                    cc.vprefixes ?: listOf(""),
                    cc.vpatterns ?: emptyList(),
                )
            }
            val alternative = if (c.vdefault_name == "USA") "United States of America" else ""
            infos.add(CountryInfo(c.vdefault_name, c.viso2, alternative, codes, c.is_hidden))
        }
        Countries.setList(infos)
    }

    /** All dialogs of the account (main list and archive). */
    suspend fun loadDialogs(client: MtpClient, onProgress: (Int) -> Unit = {}): List<ChatEntry> {
        val result = ArrayList<ChatEntry>()
        val seen = HashSet<PeerId>()
        for (folder in listOf(0, 1)) {
            var offsetDate = 0
            var offsetId = 0
            var offsetPeer: MTPInputPeer = MTPDinputPeerEmpty
            while (true) {
                val dialogs = client.invoke(
                    MTPmessages_GetDialogs(
                        vfolder_id = folder,
                        voffset_date = offsetDate,
                        voffset_id = offsetId,
                        voffset_peer = offsetPeer,
                        vlimit = 100,
                        vhash = 0,
                    ),
                )
                val (list, messages, chats, users) = when (dialogs) {
                    is MTPDmessages_dialogs -> Quad(dialogs.vdialogs, dialogs.vmessages, dialogs.vchats, dialogs.vusers)
                    is MTPDmessages_dialogsSlice -> Quad(dialogs.vdialogs, dialogs.vmessages, dialogs.vchats, dialogs.vusers)
                    is MTPDmessages_dialogsNotModified -> break
                }
                val peers = ParsePeersLists(users, chats)
                val forums = HashSet<PeerId>()
                for (chat in chats) if (chat is MTPDchannel && chat.is_forum) forums.add(Peer_.fromChannel(chat.vid))
                var lastDate = 0
                var lastId = 0
                var lastPeer: MTPInputPeer = MTPDinputPeerEmpty
                for (dialog in list) {
                    val d = dialog as? MTPDdialog ?: continue
                    val peerId = ParsePeerId(d.vpeer)
                    val peer = peers[peerId] ?: continue
                    lastId = d.vtop_message
                    lastPeer = peer.input()
                    for (m in messages) {
                        val (mid, mdate, mpeer) = when (m) {
                            is MTPDmessage -> Triple(m.vid, m.vdate, m.vpeer_id)
                            is MTPDmessageService -> Triple(m.vid, m.vdate, m.vpeer_id)
                            is MTPDmessageEmpty -> continue
                        }
                        if (mid == d.vtop_message && ParsePeerId(mpeer) == peerId) lastDate = mdate
                    }
                    if (!seen.add(peerId)) continue
                    val user = peer.user()
                    val chat = peer.chat()
                    val type = if (user != null) DialogTypeFromUser(user) else DialogTypeFromChat(chat!!)
                    val title = when {
                        user != null && user.isSelf -> "Saved Messages"
                        else -> peer.name().ifEmpty { "Deleted Account" }
                    }
                    result.add(
                        ChatEntry(
                            input = peer.input(),
                            peerId = peerId,
                            title = title,
                            username = user?.username ?: chat?.username ?: "",
                            type = type,
                            isForum = peerId in forums,
                            archived = folder == 1,
                        ),
                    )
                }
                onProgress(result.size)
                val finished = dialogs is MTPDmessages_dialogs || list.isEmpty() || lastDate == 0
                if (finished) break
                offsetDate = lastDate
                offsetId = lastId
                offsetPeer = lastPeer
            }
        }
        return result
    }

    /** The topics of a forum supergroup. */
    suspend fun loadTopics(client: MtpClient, peer: MTPInputPeer): List<TopicEntry> {
        val result = ArrayList<TopicEntry>()
        var offsetDate = 0
        var offsetId = 0
        var offsetTopic = 0
        while (true) {
            val topics = client.invoke(MTPmessages_GetForumTopics(vpeer = peer, voffset_date = offsetDate, voffset_id = offsetId, voffset_topic = offsetTopic, vlimit = 100)) as MTPDmessages_forumTopics
            var added = 0
            for (t in topics.vtopics) {
                val topic = t as? MTPDforumTopic ?: continue
                if (result.any { it.id == topic.vid }) continue
                result.add(TopicEntry(topic.vid, topic.vtitle))
                added++
                offsetTopic = topic.vid
                offsetId = topic.vtop_message
                offsetDate = topics.vmessages.firstNotNullOfOrNull { m ->
                    if (m is MTPDmessage && m.vid == topic.vtop_message) m.vdate else null
                } ?: topic.vdate
            }
            if (added == 0 || result.size >= topics.vcount) break
        }
        return result
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
