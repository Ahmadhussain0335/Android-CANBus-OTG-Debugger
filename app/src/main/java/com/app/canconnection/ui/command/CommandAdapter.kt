package com.app.canconnection.ui.command

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.app.canconnection.databinding.ItemCommandBinding
import com.app.canconnection.hardware.CanFrameBuilder
import java.util.UUID

/**
 * State holder for a single card in the horizontal command-card strip.
 *
 * A stable [id] (UUID) is assigned at construction so [RecyclerView] can use
 * [setHasStableIds] and avoid re-drawing unrelated cards when one card's periodic
 * state changes — this preserves user-typed CAN ID / data text in other cards.
 */
data class CommandItem(
    val id: String = UUID.randomUUID().toString(),
    val canIdHex: String = "",
    var isPeriodic: Boolean = false,
    var intervalSeconds: Int = 1,
    var hasEverRun: Boolean = false
)

/**
 * RecyclerView adapter for the horizontal command-card strip on [CommandActivity].
 *
 * Each card lets the user enter a CAN ID and data bytes and send them once or
 * on a repeating interval. Periodic transmission is driven by a [Handler] + [Runnable]
 * pair stored in [periodicJobs], keyed by the card's UUID. All timers are cancelled
 * together via [stopAll] when the connection is dropped.
 *
 * The remove button is hidden (INVISIBLE) rather than GONE when only one card remains
 * so the card layout does not shift. Cards cannot be removed below 1.
 */
class CommandAdapter(
    private val frameType: CanFrameBuilder.FrameType,
    private val onSend: (canIdHex: String, dataHex: String) -> Unit,
    private val onValidationError: (message: String) -> Unit
) : RecyclerView.Adapter<CommandAdapter.ViewHolder>() {

    private val items = mutableListOf<CommandItem>()
    private val periodicJobs = mutableMapOf<String, Pair<Handler, Runnable>>()

    init { setHasStableIds(true) }

    fun addItem(item: CommandItem) {
        items.add(item)
        notifyItemInserted(items.lastIndex)
        if (items.size == 2) notifyItemChanged(0)
    }

    fun removeItem(position: Int) {
        if (items.size <= 1) return
        stopPeriodic(items[position].id)
        items.removeAt(position)
        notifyItemRemoved(position)
        if (items.size == 1) notifyItemChanged(0)
    }

    fun stopAll() {
        periodicJobs.keys.toList().forEach { id ->
            periodicJobs[id]?.let { (handler, runnable) -> handler.removeCallbacks(runnable) }
        }
        periodicJobs.clear()
    }

    private fun startPeriodic(item: CommandItem, position: Int, canId: String, data: String) {
        val idError = validateCanId(canId)
        if (idError != null) { onValidationError(idError); return }
        if (data.isBlank()) { onValidationError("Enter data bytes"); return }
        val intervalSec = item.intervalSeconds.coerceAtLeast(1)
        stopPeriodic(item.id)
        val intervalMs = intervalSec * 1000L
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                onSend(canId, data)
                handler.postDelayed(this, intervalMs)
            }
        }
        handler.postDelayed(runnable, intervalMs)
        periodicJobs[item.id] = Pair(handler, runnable)
        item.hasEverRun = true
        if (position != RecyclerView.NO_ID.toInt()) notifyItemChanged(position)
    }

    private fun stopPeriodic(itemId: String) {
        periodicJobs[itemId]?.let { (handler, runnable) -> handler.removeCallbacks(runnable) }
        periodicJobs.remove(itemId)
    }

    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = items[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemCommandBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(
            item            = item,
            showRemove      = items.size > 1,
            isRunning       = periodicJobs.containsKey(item.id),
            onRemove        = { removeItem(holder.bindingAdapterPosition) },
            onSendOnce      = { canId, data -> handleSendOnce(canId, data) },
            onStartPeriodic = { canId, data ->
                startPeriodic(item, holder.bindingAdapterPosition, canId, data)
            },
            onStopPeriodic  = {
                stopPeriodic(item.id)
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_ID.toInt()) notifyItemChanged(pos)
            }
        )
    }

    private fun handleSendOnce(canIdText: String, dataText: String) {
        val idError = validateCanId(canIdText)
        if (idError != null) { onValidationError(idError); return }
        if (dataText.isBlank()) { onValidationError("Enter data bytes"); return }
        onSend(canIdText, dataText)
    }

    private fun validateCanId(text: String): String? {
        if (text.isBlank()) return "CAN ID is required"
        val value = try { text.trim().toLong(16) }
                    catch (_: NumberFormatException) { return "Invalid hex — use 0-9 and A-F" }
        if (value < 0) return "CAN ID must be positive"
        return when (frameType) {
            CanFrameBuilder.FrameType.STANDARD ->
                if (value > 0x7FF) "Standard ID must be ≤ 7FF" else null
            CanFrameBuilder.FrameType.EXTENDED ->
                if (value > 0x1FFFFFFF) "Extended ID must be ≤ 1FFFFFFF" else null
        }
    }

    class ViewHolder(private val b: ItemCommandBinding) : RecyclerView.ViewHolder(b.root) {

        private var intervalWatcher: TextWatcher? = null

        fun bind(
            item: CommandItem,
            showRemove: Boolean,
            isRunning: Boolean,
            onRemove: () -> Unit,
            onSendOnce: (String, String) -> Unit,
            onStartPeriodic: (String, String) -> Unit,
            onStopPeriodic: () -> Unit
        ) {
            if (b.editCanId.text.isNullOrEmpty() && item.canIdHex.isNotEmpty()) {
                b.editCanId.setText(item.canIdHex)
            }
            b.btnRemove.visibility = if (showRemove) View.VISIBLE else View.INVISIBLE
            b.btnRemove.setOnClickListener { onRemove() }

            b.checkPeriodic.setOnCheckedChangeListener(null)
            b.checkPeriodic.isChecked = item.isPeriodic
            b.layoutInterval.visibility = if (item.isPeriodic) View.VISIBLE else View.GONE

            intervalWatcher?.let { b.editInterval.removeTextChangedListener(it) }
            b.editInterval.setText(item.intervalSeconds.toString())
            intervalWatcher = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    item.intervalSeconds = s.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            }
            b.editInterval.addTextChangedListener(intervalWatcher)

            b.checkPeriodic.setOnCheckedChangeListener { _, isChecked ->
                item.isPeriodic = isChecked
                b.layoutInterval.visibility = if (isChecked) View.VISIBLE else View.GONE
                if (isChecked && item.hasEverRun) {
                    onStartPeriodic(b.editCanId.text.toString().trim(), b.editDataBytes.text.toString().trim())
                } else if (!isChecked) {
                    onStopPeriodic()
                }
            }

            b.btnSend.isEnabled = !isRunning
            b.btnSend.text = if (isRunning) "Sending…" else "Send"
            b.btnSend.setOnClickListener {
                val canId = b.editCanId.text.toString().trim()
                val data  = b.editDataBytes.text.toString().trim()
                if (b.checkPeriodic.isChecked) onStartPeriodic(canId, data) else onSendOnce(canId, data)
            }
        }
    }
}
