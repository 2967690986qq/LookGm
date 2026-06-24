// MatchHistoryAdapter.kt - 对局历史列表适配器（支持中英文等级映射 + DiffUtil）
package com.gameai.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gameai.R
import com.gameai.databinding.ItemMatchHistoryBinding
import com.gameai.db.MatchEntity
import com.gameai.ui.fragments.GameHistoryFragment
import java.text.SimpleDateFormat
import java.util.*

class MatchHistoryAdapter(
    private val onItemClick: (MatchEntity) -> Unit
) : ListAdapter<MatchEntity, MatchHistoryAdapter.ViewHolder>(DiffCallback) {

    private val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

    fun getMatchAt(position: Int): MatchEntity? {
        return if (position in 0 until itemCount) getItem(position) else null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMatchHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemMatchHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(match: MatchEntity) {
            binding.tvMatchInfo.text = "${match.heroName} | ${match.position}"
            binding.tvMatchScore.text = "${match.totalScore}分"
            binding.tvMatchGrade.text = GameHistoryFragment.getGradeShortName(match.grade)
            binding.tvMatchKda.text = "${match.kdaKills}/${match.kdaDeaths}/${match.kdaAssists}"
            binding.tvMatchTime.text = dateFormat.format(Date(match.startTime))

            // 等级颜色（兼容中文和拉丁等级）
            val color = GameHistoryFragment.getGradeColor(match.grade)
            binding.tvMatchGrade.setTextColor(color)
            binding.tvMatchScore.setTextColor(color)

            // 胜利/失败指示
            binding.tvMatchInfo.setTextColor(
                if (match.isVictory) 0xFFE0E0E0.toInt() else 0xFFFF5252.toInt()
            )

            binding.root.setOnClickListener { onItemClick(match) }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<MatchEntity>() {
        override fun areItemsTheSame(oldItem: MatchEntity, newItem: MatchEntity): Boolean {
            return oldItem.matchId == newItem.matchId
        }

        override fun areContentsTheSame(oldItem: MatchEntity, newItem: MatchEntity): Boolean {
            return oldItem == newItem
        }
    }
}
