package org.tgbackup.export

import org.tgbackup.export.data.*
import org.tgbackup.export.output.*
import org.tgbackup.mtproto.tl.MTPDinputPeerChannel
import kotlin.test.Test
import kotlin.test.assertTrue

class WriterSmokeTest {
    private val thumbs = object : ImageThumbs {
        override fun size(path: String): Pair<Int, Int>? =
            runCatching { javax.imageio.ImageIO.read(java.io.File(path)) }.getOrNull()?.let { it.width to it.height }

        override fun scaleAndSave(path: String, width: Int, height: Int, target: String, format: String?, quality: Int): Boolean {
            val src = javax.imageio.ImageIO.read(java.io.File(path)) ?: return false
            val dst = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            dst.createGraphics().drawImage(src.getScaledInstance(width, height, java.awt.Image.SCALE_SMOOTH), 0, 0, null)
            return javax.imageio.ImageIO.write(dst, format ?: "jpg", java.io.File(target))
        }
    }

    @Test
    fun writesSingleChat() {
        ExportTimeZone.zone = java.util.TimeZone.getTimeZone("Asia/Jerusalem")
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val settings = Settings()
        settings.path = dir.absolutePath + "/"
        settings.format = Format.HtmlAndJson
        settings.singlePeer = MTPDinputPeerChannel(123, 456)
        settings.types = Settings.Type.AnyChatsMask
        val env = Environment()
        val writer = CreateWriter(settings.format, thumbs)
        val stats = Stats()
        check(writer.start(settings, env, stats).isSuccess())
        val user = User().apply {
            bareId = 777
            info.userId = 777
            info.firstName = "John"
            info.lastName = "Preston"
            username = "preston"
        }
        val dialog = DialogInfo().apply {
            type = DialogInfo.Type.PublicChannel
            name = "Test \"channel\" <b>"
            peerId = Peer_.fromChannel(123)
        }
        check(writer.writeDialogsStart(DialogsInfo().apply { chats.add(dialog) }).isSuccess())
        check(writer.writeDialogStart(dialog).isSuccess())
        val slice = MessagesSlice()
        slice.peers[Peer_.fromUser(777)] = Peer(user)
        var id = 1
        fun msg() = Message().apply {
            this.id = id++
            date = 1_700_000_000 + id * 100
            fromId = Peer_.fromUser(777)
            peerId = Peer_.fromChannel(123)
        }
        slice.list.add(msg().apply { text = ParseText("Hello https://telegram.org bold\nline2   x", emptyList()) })
        slice.list.add(msg().apply {
            text = mutableListOf(TextPart(TextPart.Type.Bold, "b"), TextPart(TextPart.Type.TextUrl, "link", "https://example.com/a b"))
            reactions.add(Reaction().apply { type = Reaction.Type.Emoji; emoji = "👍"; count = 3 })
        })
        slice.list.add(msg().apply { action.content = ActionChannelCreate("Channel") })
        slice.list.add(msg().apply { media.content = Poll().apply { question = ParseText("Q?", emptyList()); answers.add(Poll.Answer().apply { text = ParseText("A", emptyList()); votes = 2 }) ; totalVotes = 2 } })
        slice.list.add(msg().apply { media.content = Document().apply { name = "a.pdf"; mime = "application/pdf"; file.size = 1234; file.skipReason = org.tgbackup.export.data.File.SkipReason.FileType } })
        slice.list.add(msg().apply { media.content = Photo().apply { image.width = 800; image.height = 600; image.file.skipReason = org.tgbackup.export.data.File.SkipReason.FileSize; image.file.size = 99999 } })
        slice.list.add(msg().apply { action.content = ActionPhoneCall().apply { duration = 30; state = ActionPhoneCall.State.Hangup } })
        slice.list.add(msg().apply { media.content = GeoPoint().apply { latitude = 31.7; longitude = 35.2; valid = true } })
        slice.list.add(msg().apply { forwarded = true; forwardedFromName = "Someone Else"; forwardedDate = 1_600_000_000; text = ParseText("fwd", emptyList()) })
        slice.list.add(msg().apply {
            richMessage = RichMessage().apply {
                blocks.add(RichBlock().apply { kind = RichBlock.Kind.Heading; headingLevel = 1; text = RichText().apply { type = RichText.Type.Plain; this.text = "Title" } })
                blocks.add(RichBlock().apply { kind = RichBlock.Kind.Paragraph; text = RichText().apply { type = RichText.Type.Plain; this.text = "Para" } })
            }
        })
        check(writer.writeDialogSlice(slice).isSuccess())
        check(writer.writeDialogEnd().isSuccess())
        check(writer.writeDialogsEnd().isSuccess())
        check(writer.finish().isSuccess())
        val html = java.io.File(dir, "messages.html").readText()
        val json = java.io.File(dir, "result.json").readText()
        println(html.take(4000))
        println(json.take(3000))
        assertTrue(java.io.File(dir, "css/style.css").exists())
        assertTrue(html.contains("Test &quot;channel&quot;"))
        assertTrue(json.startsWith("{"))
        println("FILES: " + stats.filesCount() + " BYTES: " + stats.bytesCount() + " MAIN: " + writer.mainFilePath())
    }
}
