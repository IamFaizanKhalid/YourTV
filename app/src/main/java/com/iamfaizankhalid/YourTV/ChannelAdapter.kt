package com.iamfaizankhalid.YourTV

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChannelAdapter(
    private val entries: List<Entry>,
    private val onChannelSelected: (Int) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    data class Entry(val channel: Channel, val index: Int)

    private val icons = mutableMapOf<Int, Bitmap>()

    fun setChannelIcon(index: Int, icon: Bitmap) {
        val position = entries.indexOfFirst { it.index == index }
        if (position < 0) return

        icons[index] = icon
        notifyItemChanged(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder =
        ChannelViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_channel, parent, false)
        )

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val entry = entries[position]
        holder.bind(
            entry.channel,
            entry.index,
            icons[entry.index],
            onChannelSelected
        )
    }

    override fun getItemCount(): Int = entries.size

    class ChannelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(
            channel: Channel,
            index: Int,
            icon: Bitmap?,
            onSelected: (Int) -> Unit
        ) {
            itemView.findViewById<TextView>(R.id.channelNumber).text = (index + 1).toString()
            itemView.findViewById<TextView>(R.id.channelName).text = channel.name
            itemView.findViewById<ImageView>(R.id.channelIcon).setImageBitmap(icon)
            itemView.setOnClickListener { onSelected(index) }
        }
    }
}
