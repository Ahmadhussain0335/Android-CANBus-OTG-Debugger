package com.app.canconnection.ui.library

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.app.canconnection.R
import com.app.canconnection.data.model.SavedCommand
import com.app.canconnection.databinding.ItemSavedCommandBinding

/**
 * RecyclerView adapter for the Command Library list on [SavedCommandsActivity].
 *
 * Supports single-item selection: the selected row gets a semi-transparent blue
 * background overlay computed from [R.color.color_info] with 20 % opacity.
 * Selection is cleared automatically via [clearSelectionIfDeleted] when the selected
 * command is deleted so [SavedCommandsActivity.selectedCommand] is always in sync.
 * The [onSelect] callback updates the activity's current selection; [onDelete] removes
 * the command from the ViewModel and persists the change.
 */
class SavedCommandsAdapter(
    private val onSelect: (SavedCommand) -> Unit,
    private val onDelete: (id: String) -> Unit
) : RecyclerView.Adapter<SavedCommandsAdapter.ViewHolder>() {

    private val items = mutableListOf<SavedCommand>()
    private var selectedId: String? = null

    fun submitList(list: List<SavedCommand>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun clearSelectionIfDeleted(commands: List<SavedCommand>) {
        if (selectedId != null && commands.none { it.id == selectedId }) selectedId = null
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemSavedCommandBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val cmd = items[position]
        holder.bind(cmd, isSelected = cmd.id == selectedId,
            onClick = {
                val prev = selectedId
                selectedId = cmd.id
                if (prev != null) {
                    val prevPos = items.indexOfFirst { it.id == prev }
                    if (prevPos >= 0) notifyItemChanged(prevPos)
                }
                notifyItemChanged(position)
                onSelect(cmd)
            },
            onDelete = { onDelete(cmd.id) }
        )
    }

    class ViewHolder(private val b: ItemSavedCommandBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(cmd: SavedCommand, isSelected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
            b.tvCommandName.text    = cmd.name
            b.tvCommandDetails.text = "CAN: ${cmd.canIdHex}  ·  Data: ${cmd.dataHex}"
            val base = ContextCompat.getColor(b.root.context, R.color.color_info)
            b.root.setBackgroundColor(
                if (isSelected) (base and 0x00FFFFFF) or 0x33000000 else Color.TRANSPARENT
            )
            b.root.setOnClickListener { onClick() }
            b.btnDeleteCommand.setOnClickListener { onDelete() }
        }
    }
}
