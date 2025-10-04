package eternalScript.core.dialog

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

class CustomDialog {
    companion object {
        fun builder(block: CustomDialog.() -> Unit): CustomDialog {
            val builder = CustomDialog().apply(block)
            return builder
        }
    }

    var type: DialogType = DialogType.notice()
    var title: Component = Component.empty()
    var externalTitle: Component? = null
    var canCloseWithEscape: Boolean = true
    var pause: Boolean = false
    var afterAction: DialogBase.DialogAfterAction = DialogBase.DialogAfterAction.CLOSE
    var body: List<DialogBody> = emptyList()
    var inputs: List<DialogInput> = emptyList()

    fun base() = DialogBase.create(
        title,
        externalTitle,
        canCloseWithEscape,
        pause,
        afterAction,
        body,
        inputs
    )

    fun dialog() = Dialog.create { builder ->
        builder.empty().type(type).base(base())
    }

    fun show(player: Player) {
        player.showDialog(dialog())
    }

    fun close(player: Player) {
        player.closeDialog()
    }
}