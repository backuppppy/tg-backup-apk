// Port of tdesktop Telegram/SourceFiles/export/export_settings.h/.cpp
package org.tgbackup.export

import org.tgbackup.export.output.Format
import org.tgbackup.mtproto.tl.MTPDinputPeerEmpty
import org.tgbackup.mtproto.tl.MTPInputPeer

class MediaSettings {
    object Type {
        const val Photo = 0x01
        const val Video = 0x02
        const val VoiceMessage = 0x04
        const val VideoMessage = 0x08
        const val Sticker = 0x10
        const val GIF = 0x20
        const val File = 0x40

        const val MediaMask = Photo or Video or VoiceMessage or VideoMessage
        const val AllMask = MediaMask or Sticker or GIF or File
    }

    var types: Int = DefaultTypes()
    var sizeLimit: Long = 8L * 1024 * 1024

    fun validate(): Boolean {
        if ((types or Type.AllMask) != Type.AllMask) return false
        if (sizeLimit < 0 || sizeLimit > kMaxFileSize) return false
        return true
    }

    fun copy(): MediaSettings = MediaSettings().also {
        it.types = types
        it.sizeLimit = sizeLimit
    }

    companion object {
        const val kMaxFileSize: Long = 4000L * 1024 * 1024
        fun DefaultTypes(): Int = Type.Photo
    }
}

class Settings {
    object Type {
        const val PersonalInfo = 0x001
        const val Userpics = 0x002
        const val Contacts = 0x004
        const val Sessions = 0x008
        const val OtherData = 0x010
        const val PersonalChats = 0x020
        const val BotChats = 0x040
        const val PrivateGroups = 0x080
        const val PublicGroups = 0x100
        const val PrivateChannels = 0x200
        const val PublicChannels = 0x400
        const val Stories = 0x800
        const val ProfileMusic = 0x1000

        const val GroupsMask = PrivateGroups or PublicGroups
        const val ChannelsMask = PrivateChannels or PublicChannels
        const val GroupsChannelsMask = GroupsMask or ChannelsMask
        const val NonChannelChatsMask = PersonalChats or BotChats or PrivateGroups
        const val AnyChatsMask = PersonalChats or BotChats or GroupsChannelsMask
        const val NonChatsMask = PersonalInfo or Userpics or Contacts or Stories or ProfileMusic or Sessions
        const val AllMask = NonChatsMask or OtherData or AnyChatsMask
    }

    var path: String = ""
    var forceSubPath = false
    var format: Format = Format.Html

    var types: Int = DefaultTypes()
    var fullChats: Int = DefaultFullChats()
    var media = MediaSettings()

    var singlePeer: MTPInputPeer = MTPDinputPeerEmpty
    var singlePeerFrom: Int = 0
    var singlePeerTill: Int = 0

    var singleTopicRootId: Int = 0
    var singleTopicPeerId: Long = 0
    var singleTopicTitle: String = ""

    var availableAt: Int = 0

    fun onlySinglePeer(): Boolean = singlePeer !== MTPDinputPeerEmpty

    fun onlySingleTopic(): Boolean = onlySinglePeer() && singleTopicRootId != 0

    fun copy(): Settings = Settings().also {
        it.path = path
        it.forceSubPath = forceSubPath
        it.format = format
        it.types = types
        it.fullChats = fullChats
        it.media = media.copy()
        it.singlePeer = singlePeer
        it.singlePeerFrom = singlePeerFrom
        it.singlePeerTill = singlePeerTill
        it.singleTopicRootId = singleTopicRootId
        it.singleTopicPeerId = singleTopicPeerId
        it.singleTopicTitle = singleTopicTitle
        it.availableAt = availableAt
    }

    fun validate(): Boolean {
        val MustBeFull = Type.PersonalChats or Type.BotChats
        val MustNotBeFull = Type.PublicGroups or Type.PublicChannels
        if ((types or Type.AllMask) != Type.AllMask) return false
        if ((fullChats or Type.AllMask) != Type.AllMask) return false
        if ((fullChats and MustBeFull) != MustBeFull) return false
        if ((fullChats and MustNotBeFull) != 0) return false
        if (format != Format.Html && format != Format.Json) return false
        if (!media.validate()) return false
        if (singlePeerTill > 0 && singlePeerTill <= singlePeerFrom) return false
        return true
    }

    companion object {
        fun DefaultTypes(): Int = Type.PersonalInfo or Type.Userpics or Type.Contacts or Type.Stories or
            Type.ProfileMusic or Type.PersonalChats or Type.PrivateGroups

        fun DefaultFullChats(): Int = Type.PersonalChats or Type.BotChats
    }
}

class Environment {
    var internalLinksDomain: String = "https://t.me/"
    var aboutTelegram: String = ""
    var aboutContacts: String = ""
    var aboutFrequent: String = ""
    var aboutSessions: String = ""
    var aboutWebSessions: String = ""
    var aboutChats: String = ""
    var aboutLeftChats: String = ""
}
