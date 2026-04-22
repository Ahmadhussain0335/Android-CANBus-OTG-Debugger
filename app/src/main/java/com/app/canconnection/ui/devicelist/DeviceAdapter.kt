package com.app.canconnection.ui.devicelist

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.app.canconnection.R
import com.app.canconnection.data.model.CanDevice
import com.app.canconnection.databinding.ItemDeviceBinding

/**
 * RecyclerView adapter for the device discovery list on [DeviceListActivity].
 *
 * Displays each detected [CanDevice] as a card showing the driver/device name
 * and its USB VID/PID. The most-recently tapped card is highlighted with a blue
 * stroke; the previous card's stroke is cleared before the new one is applied so
 * only one card appears selected at a time. The [onItemClick] callback lets the
 * activity navigate to [ConfigureActivity] with the chosen device.
 */
class DeviceAdapter(
    private val onItemClick: (CanDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    private var items: List<CanDevice> = emptyList()
    private var selectedPosition: Int = -1

    fun submitList(list: List<CanDevice>) {
        items = list
        selectedPosition = -1
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position == selectedPosition) {
            val prev = selectedPosition
            selectedPosition = position
            if (prev >= 0) notifyItemChanged(prev)
            notifyItemChanged(position)
            onItemClick(items[position])
        }
    }

    class ViewHolder(private val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(device: CanDevice, selected: Boolean, onClick: () -> Unit) {
            binding.tvDriverName.text = device.displayName
            binding.tvVidPid.text = "VID:%04X PID:%04X".format(device.vendorId, device.productId)
            val ctx = binding.root.context
            val density = ctx.resources.displayMetrics.density
            // Stroke width is specified in dp units via the card's strokeWidth API
            if (selected) {
                binding.cardView.strokeColor = ctx.getColor(R.color.color_info)
                binding.cardView.strokeWidth = (4 * density).toInt()
            } else {
                binding.cardView.strokeColor = android.graphics.Color.TRANSPARENT
                binding.cardView.strokeWidth = 0
            }
            binding.root.setOnClickListener { onClick() }
        }
    }
}
