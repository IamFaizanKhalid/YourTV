package com.iamfaizankhalid.YourTV

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class ChannelListAdapter(
    private val currentIndex: Int,
    private val onChannelSelected: (Int) -> Unit
) : RecyclerView.Adapter<ChannelListAdapter.ChannelViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder =
        ChannelViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_channel_row, parent, false)
        )

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(Channels.all[position], position, currentIndex, onChannelSelected)
    }

    override fun getItemCount(): Int = Channels.all.size

    class ChannelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(
            channel: Channel,
            index: Int,
            currentIndex: Int,
            onSelected: (Int) -> Unit
        ) {
            itemView.findViewById<TextView>(R.id.channelNumber).text = (index + 1).toString()
            itemView.findViewById<TextView>(R.id.channelName).apply {
                text = channel.name
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (index == currentIndex) R.color.accent else R.color.text_primary
                    )
                )
            }
            itemView.setOnClickListener { onSelected(index) }
        }
    }
}
