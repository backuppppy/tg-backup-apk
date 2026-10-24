// Port of tdesktop Telegram/SourceFiles/export/output/export_output_html_and_json.cpp
package org.tgbackup.export.output

import org.tgbackup.export.Environment
import org.tgbackup.export.Settings
import org.tgbackup.export.data.*

class HtmlAndJsonWriter(thumbs: ImageThumbs) : AbstractWriter {
    private val writers: List<AbstractWriter> = listOf(HtmlWriter(thumbs), JsonWriter())

    private fun invoke(method: (AbstractWriter) -> Result): Result {
        var result = Result.Success()
        for (writer in writers) {
            val current = method(writer)
            if (!current.isSuccess()) result = current
        }
        return result
    }

    override fun format() = Format.HtmlAndJson
    override fun start(settings: Settings, environment: Environment, stats: Stats?) = invoke { it.start(settings, environment, stats) }
    override fun writePersonal(data: PersonalInfo) = invoke { it.writePersonal(data) }
    override fun writeUserpicsStart(data: UserpicsInfo) = invoke { it.writeUserpicsStart(data) }
    override fun writeUserpicsSlice(data: UserpicsSlice) = invoke { it.writeUserpicsSlice(data) }
    override fun writeUserpicsEnd() = invoke { it.writeUserpicsEnd() }
    override fun writeStoriesStart(data: StoriesInfo) = invoke { it.writeStoriesStart(data) }
    override fun writeStoriesSlice(data: StoriesSlice) = invoke { it.writeStoriesSlice(data) }
    override fun writeStoriesEnd() = invoke { it.writeStoriesEnd() }
    override fun writeProfileMusicStart(data: ProfileMusicInfo) = invoke { it.writeProfileMusicStart(data) }
    override fun writeProfileMusicSlice(data: ProfileMusicSlice) = invoke { it.writeProfileMusicSlice(data) }
    override fun writeProfileMusicEnd() = invoke { it.writeProfileMusicEnd() }
    override fun writeContactsList(data: ContactsList) = invoke { it.writeContactsList(data) }
    override fun writeSessionsList(data: SessionsList) = invoke { it.writeSessionsList(data) }
    override fun writeOtherData(data: org.tgbackup.export.data.File) = invoke { it.writeOtherData(data) }
    override fun writeDialogsStart(data: DialogsInfo) = invoke { it.writeDialogsStart(data) }
    override fun writeDialogStart(data: DialogInfo) = invoke { it.writeDialogStart(data) }
    override fun writeDialogSlice(data: MessagesSlice) = invoke { it.writeDialogSlice(data) }
    override fun writeDialogEnd() = invoke { it.writeDialogEnd() }
    override fun writeDialogsEnd() = invoke { it.writeDialogsEnd() }
    override fun finish() = invoke { it.finish() }
    override fun mainFilePath(): String = writers.first().mainFilePath()
}
