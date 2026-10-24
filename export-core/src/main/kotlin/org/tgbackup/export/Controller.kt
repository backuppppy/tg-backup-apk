// Port of tdesktop Telegram/SourceFiles/export/export_controller.cpp
// (single chat and single topic export).
package org.tgbackup.export

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.tgbackup.export.data.DialogInfo
import org.tgbackup.export.data.DialogsInfo
import org.tgbackup.export.data.Peer_
import org.tgbackup.export.output.AbstractWriter
import org.tgbackup.export.output.CreateWriter
import org.tgbackup.export.output.ImageThumbs
import org.tgbackup.export.output.NormalizePath
import org.tgbackup.export.output.Result
import org.tgbackup.export.output.Stats
import org.tgbackup.mtproto.MtpClient

class ProcessingState {
    enum class Step { Initializing, DialogsList, PersonalInfo, Userpics, Stories, ProfileMusic, Contacts, Sessions, OtherData, Dialogs, Topic }
    enum class EntityType { Chat, SavedMessages, RepliesMessages, VerifyCodes, Topic, Other }

    var step = Step.Initializing
    var substepsPassed = 0
    var substepsNow = 0
    var substepsTotal = 0
    var entityType = EntityType.Other
    var entityName = ""
    var entityIndex = 0
    var entityCount = 0
    var itemIndex = 0
    var itemCount = 0
    var bytesRandomId = 0L
    var bytesName = ""
    var bytesLoaded = 0L
    var bytesCount = 0L
}

sealed interface ExportState {
    object Idle : ExportState
    class Processing(val state: ProcessingState) : ExportState
    class ApiError(val code: Int, val type: String) : ExportState
    class OutputError(val path: String) : ExportState
    object Cancelled : ExportState
    class Finished(val path: String, val filesCount: Int, val bytesCount: Long) : ExportState
}

/**
 * Runs one export. [topicRootId] != 0 exports a single forum topic, as
 * tdesktop's Controller constructed with a topic does.
 */
class Controller(
    client: MtpClient,
    private val thumbs: ImageThumbs,
    private val topicRootId: Int = 0,
    private val topicPeerId: Long = 0,
    private val topicTitle: String = "",
    log: (String) -> Unit = {},
) {
    private val api = ApiWrap(client, log)
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state

    private lateinit var settings: Settings
    private lateinit var environment: Environment
    private lateinit var writer: AbstractWriter
    private val stats = Stats()
    private var dialogsInfo = DialogsInfo()
    private var dialogIndex = -1
    private var messagesWritten = 0
    private var messagesCount = 0
    private val substepsInStep = IntArray(ProcessingState.Step.entries.size)
    private var substepsTotal = 0
    private var substepsPassed = 0
    private var lastProcessingStep = ProcessingState.Step.Initializing

    private fun stopped(): Boolean = when (_state.value) {
        is ExportState.Cancelled, is ExportState.ApiError, is ExportState.OutputError, is ExportState.Finished -> true
        else -> false
    }

    private fun setState(state: ExportState) {
        if (stopped()) return
        _state.value = state
    }

    private fun ioCatchError(result: Result): Boolean {
        if (!result.isSuccess()) {
            setState(ExportState.OutputError(result.path))
            throw ExportIoError(result)
        }
        return false
    }

    fun skipFile(randomId: Long) {
        if (stopped()) return
        api.skipFile(randomId)
    }

    suspend fun cancelExportFast() {
        setState(ExportState.Cancelled)
        api.cancelExportFast()
    }

    /** NormalizeSettings + startExport + the steps, until finished or failed. */
    suspend fun run(settings: Settings, environment: Environment) {
        val normalized = settings.copy()
        if (normalized.onlySinglePeer()) {
            normalized.types = Settings.Type.AnyChatsMask
            normalized.fullChats = Settings.Type.AnyChatsMask
        }
        this.settings = normalized
        this.environment = environment
        this.settings.singleTopicRootId = topicRootId
        this.settings.singleTopicPeerId = topicPeerId
        this.settings.path = NormalizePath(this.settings)
        writer = CreateWriter(this.settings.format, thumbs)
        try {
            // Initializing
            setState(ExportState.Processing(ProcessingState()))
            val info = api.startExport(this.settings, stats)
            ioCatchError(writer.start(this.settings, environment, stats))
            fillSubstepsInSteps(info.dialogsCount)
            if (this.settings.onlySingleTopic()) {
                exportTopic()
                return
            }
            collectDialogsList()
            exportDialogs()
            ioCatchError(writer.finish())
            api.finishExport()
            setFinishedState()
        } catch (e: ExportApiError) {
            setState(ExportState.ApiError(e.code, e.type))
        } catch (e: org.tgbackup.mtproto.RpcError) {
            setState(ExportState.ApiError(e.code, e.type))
        } catch (e: ExportIoError) {
            setState(ExportState.OutputError(e.result.path))
        } catch (e: ExportStopped) {
            // Cancelled elsewhere.
        } catch (e: CancellationException) {
            setState(ExportState.Cancelled)
            throw e
        }
    }

    private fun fillSubstepsInSteps(dialogsCount: Int) {
        val S = ProcessingState.Step.Initializing.javaClass
        @Suppress("UNUSED_VARIABLE") val unused = S
        substepsInStep.fill(0)
        substepsInStep[ProcessingState.Step.Initializing.ordinal] = 1
        if ((settings.types and Settings.Type.AnyChatsMask) != 0) {
            substepsInStep[ProcessingState.Step.DialogsList.ordinal] = 1
            substepsInStep[ProcessingState.Step.Dialogs.ordinal] = dialogsCount
        }
        if (settings.onlySingleTopic()) substepsInStep[ProcessingState.Step.Topic.ordinal] = 1
        substepsTotal = substepsInStep.sum()
    }

    private fun prepareState(step: ProcessingState.Step, fill: (ProcessingState) -> Unit = {}): ProcessingState {
        if (step != lastProcessingStep) {
            substepsPassed += substepsInStep[lastProcessingStep.ordinal]
            lastProcessingStep = step
        }
        val result = ProcessingState()
        fill(result)
        result.step = step
        result.substepsPassed = substepsPassed
        result.substepsNow = substepsInStep[lastProcessingStep.ordinal]
        result.substepsTotal = substepsTotal
        return result
    }

    private suspend fun collectDialogsList() {
        setState(ExportState.Processing(stateDialogsList(0)))
        dialogsInfo = api.requestDialogsList { count ->
            if (count > 0) setState(ExportState.Processing(stateDialogsList(count - 1)))
            !stopped()
        }
    }

    private fun stateDialogsList(processed: Int) = prepareState(ProcessingState.Step.DialogsList) {
        it.entityIndex = processed
        it.entityCount = maxOf(processed, substepsInStep[ProcessingState.Step.Dialogs.ordinal])
    }

    private suspend fun exportDialogs() {
        ioCatchError(writer.writeDialogsStart(dialogsInfo))
        while (true) {
            val index = ++dialogIndex
            val info = dialogsInfo.item(index) ?: break
            api.requestMessages(
                info,
                start = { dialog ->
                    ioCatchError(writer.writeDialogStart(dialog))
                    messagesWritten = 0
                    messagesCount = dialog.messagesCountPerSplit.sum()
                    setState(ExportState.Processing(stateDialogs(DownloadProgress())))
                    !stopped()
                },
                progress = { progress ->
                    setState(ExportState.Processing(stateDialogs(progress)))
                    !stopped()
                },
                slice = { slice ->
                    ioCatchError(writer.writeDialogSlice(slice))
                    messagesWritten += slice.list.size
                    setState(ExportState.Processing(stateDialogs(DownloadProgress())))
                    !stopped()
                },
            )
            ioCatchError(writer.writeDialogEnd())
        }
        ioCatchError(writer.writeDialogsEnd())
    }

    private fun stateDialogs(progress: DownloadProgress) = prepareState(ProcessingState.Step.Dialogs) { result ->
        val dialog = dialogsInfo.item(dialogIndex)!!
        result.entityIndex = dialogIndex
        result.entityCount = dialogsInfo.chats.size + dialogsInfo.left.size
        result.entityName = dialog.name
        result.entityType = when (dialog.type) {
            DialogInfo.Type.Self -> ProcessingState.EntityType.SavedMessages
            DialogInfo.Type.Replies -> ProcessingState.EntityType.RepliesMessages
            DialogInfo.Type.VerifyCodes -> ProcessingState.EntityType.VerifyCodes
            else -> ProcessingState.EntityType.Chat
        }
        result.itemIndex = messagesWritten + progress.itemIndex
        result.itemCount = maxOf(messagesCount, result.itemIndex)
        result.bytesRandomId = progress.randomId
        if (progress.path.isNotEmpty()) result.bytesName = progress.path.substring(progress.path.lastIndexOf('/') + 1)
        result.bytesLoaded = progress.ready
        result.bytesCount = progress.total
    }

    private suspend fun exportTopic() {
        val topicInfo = DialogInfo()
        topicInfo.type = DialogInfo.Type.PublicSupergroup
        topicInfo.name = topicTitle
        topicInfo.peerId = topicPeerId
        topicInfo.relativePath = ""
        ioCatchError(writer.writeDialogStart(topicInfo))
        api.requestTopicMessages(
            topicPeerId,
            settings.singlePeer,
            topicRootId,
            start = { count ->
                messagesWritten = 0
                messagesCount = count
                setState(ExportState.Processing(stateTopic(DownloadProgress())))
                !stopped()
            },
            progress = { progress ->
                setState(ExportState.Processing(stateTopic(progress)))
                !stopped()
            },
            slice = { slice ->
                ioCatchError(writer.writeDialogSlice(slice))
                messagesWritten += slice.list.size
                setState(ExportState.Processing(stateTopic(DownloadProgress())))
                !stopped()
            },
        )
        ioCatchError(writer.writeDialogEnd())
        ioCatchError(writer.finish())
        api.finishExport()
        setFinishedState()
    }

    private fun stateTopic(progress: DownloadProgress) = prepareState(ProcessingState.Step.Topic) { result ->
        result.entityType = ProcessingState.EntityType.Topic
        result.entityName = topicTitle
        result.entityIndex = 0
        result.entityCount = 1
        result.itemIndex = messagesWritten + progress.itemIndex
        result.itemCount = maxOf(messagesCount, result.itemIndex)
        result.bytesRandomId = progress.randomId
        if (progress.path.isNotEmpty()) result.bytesName = progress.path.substring(progress.path.lastIndexOf('/') + 1)
        result.bytesLoaded = progress.ready
        result.bytesCount = progress.total
    }

    private fun setFinishedState() {
        setState(ExportState.Finished(writer.mainFilePath(), stats.filesCount(), stats.bytesCount()))
    }

    @Suppress("unused")
    private fun unusedPeer() = Peer_.fromUser(0)
}
