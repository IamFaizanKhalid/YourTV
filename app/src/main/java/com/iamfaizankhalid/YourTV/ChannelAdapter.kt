package com.iamfaizankhalid.YourTV

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChannelAdapter(
    private val channels: List<Channel>,
    private val onChannelSelected: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row
    private data class Header(val title: String) : Row()
    private data class ChannelRow(val channel: Channel, val index: Int) : Row()

    private val rows: List<Row> = buildList {
        listOf("International" to "International News", "Pakistan" to "Pakistani News").forEach { (group, title) ->
            channels.mapIndexedNotNull { index, channel ->
                if (channel.group == group) ChannelRow(channel, channels.indexOf(channel)) else null
            }.takeIf { it.isNotEmpty() }?.let { groupChannels ->
                add(Header(title))
                addAll(groupChannels)
            }
        }
    }

    private val icons = mutableMapOf<Int, Bitmap>()

    fun setChannelIcon(index: Int, icon: Bitmap) {
        icons[index] = icon
        val position = rows.indexOfFirst { it is ChannelRow && it.index == index }
        if (position >= 0) notifyItemChanged(position)
    }

    override fun getItemViewType(position: Int): Int = if (rows[position] is Header) 0 else 1

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) HeaderViewHolder(inflater.inflate(R.layout.item_section_header, parent, false))
        else ChannelViewHolder(inflater.inflate(R.layout.item_channel, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Header -> (holder as HeaderViewHolder).bind(row.title)
            is ChannelRow -> (holder as ChannelViewHolder).bind(
                row.channel,
                row.index,
                icons[row.index],
                onChannelSelected
            )
        }
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(title: String) {
            itemView.findViewById<TextView>(R.id.sectionTitle).text = title
        }
    }

    class ChannelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(
            channel: Channel,
            index: Int,
            icon: Bitmap?,
            onSelected: (Int) -> Unit
        ) {
            itemView.findViewById<TextView>(R.id.channelNumber).text = (index + 1).toString()
            itemView.findViewById<TextView>(R.id.channelName).text = channel.name
            itemView.findViewById<TextView>(R.id.channelGroup).text = channel.group
            itemView.findViewById<ImageView>(R.id.channelIcon).setImageBitmap(icon)
            itemView.setOnClickListener { onSelected(index) }
        }
    }

    fun isHeader(position: Int): Boolean = rows[position] is Header
}
