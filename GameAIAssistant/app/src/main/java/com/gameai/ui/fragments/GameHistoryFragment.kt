// GameHistoryFragment.kt - 对局历史记录页面（增强版：下拉刷新 + 滑动删除 + 等级映射）
package com.gameai.ui.fragments

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gameai.R
import com.gameai.databinding.FragmentGameHistoryBinding
import com.gameai.db.AppDatabase
import com.gameai.db.MatchEntity
import com.gameai.ui.MatchHistoryAdapter
import com.gameai.viewmodel.MainViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class GameHistoryFragment : Fragment(R.layout.fragment_game_history) {

    private var _binding: FragmentGameHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: MainViewModel
    private val adapter = MatchHistoryAdapter { match -> openReportPage(match) }
    private val dateFormatFull = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentGameHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        // RecyclerView 设置
        binding.rvMatches.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMatches.adapter = adapter

        // 下拉刷新
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refreshMatchHistory()
            binding.swipeRefresh.isRefreshing = false
        }
        binding.swipeRefresh.setColorSchemeResources(
            R.color.accent_primary,
            android.R.color.holo_blue_bright,
            android.R.color.holo_green_light
        )

        // 滑动删除
        setupSwipeToDelete()

        // 观察历史数据
        viewModel.matchHistory.observe(viewLifecycleOwner) { matches ->
            adapter.submitList(matches)
            updateStats(matches)
            binding.layoutEmpty.visibility = if (matches.isEmpty()) View.VISIBLE else View.GONE
            binding.rvMatches.visibility = if (matches.isEmpty()) View.GONE else View.VISIBLE
            binding.tvMatchCount.text = "${matches.size} 场"
        }

        // 清空按钮
        binding.btnClear.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("清空所有记录")
                .setMessage("确定要删除全部 ${viewModel.matchHistory.value?.size ?: 0} 条对局记录吗？\n\n此操作不可撤销。")
                .setPositiveButton("确认清空") { _, _ -> viewModel.clearHistory() }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun setupSwipeToDelete() {
        val swipeHandler = object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return

                val match = adapter.getMatchAt(position)
                if (match != null) {
                    // 先临时恢复（否则item立即消失）
                    adapter.notifyItemChanged(position)

                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("删除记录")
                        .setMessage("确定删除这条对局记录吗？\n\n${match.heroName} | ${match.position} | ${match.totalScore}分")
                        .setPositiveButton("删除") { _, _ ->
                            lifecycleScope.launch {
                                val db = AppDatabase.getInstance(requireContext())
                                db.matchDao().deleteMatch(match)
                                viewModel.refreshMatchHistory()
                            }
                        }
                        .setNegativeButton("取消") { _, _ ->
                            // 取消时恢复列表
                            adapter.notifyItemChanged(position)
                        }
                        .setOnDismissListener {
                            adapter.notifyItemChanged(position)
                        }
                        .show()
                }
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val itemView = viewHolder.itemView
                    val background = ColorDrawable()
                    val deleteColor = resources.getColor(R.color.status_error, null)
                    val deleteIcon = ContextCompat.getDrawable(
                        requireContext(),
                        android.R.drawable.ic_menu_delete
                    )

                    // 绘制红色背景
                    if (dX > 0) {
                        // 右滑
                        background.color = deleteColor
                        background.setBounds(
                            itemView.left, itemView.top,
                            itemView.left + dX.toInt(), itemView.bottom
                        )
                        background.draw(c)
                    } else {
                        // 左滑
                        background.color = deleteColor
                        background.setBounds(
                            itemView.right + dX.toInt(), itemView.top,
                            itemView.right, itemView.bottom
                        )
                        background.draw(c)
                    }

                    // 绘制删除文字
                    val paint = Paint().apply {
                        color = Color.WHITE
                        textSize = 36f
                        textAlign = Paint.Align.CENTER
                        isAntiAlias = true
                    }
                    val textY = itemView.top + (itemView.height / 2f) + 12f
                    if (dX > 0) {
                        val textX = itemView.left + 80f
                        c.drawText("删除", textX, textY, paint)
                    } else {
                        val textX = itemView.right - 80f
                        c.drawText("删除", textX, textY, paint)
                    }

                    super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                } else {
                    super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                }
            }
        }

        ItemTouchHelper(swipeHandler).attachToRecyclerView(binding.rvMatches)
    }

    private fun updateStats(matches: List<MatchEntity>) {
        if (matches.isEmpty()) {
            binding.tvAvgScore.text = "--"
            binding.tvBestScore.text = "--"
            binding.tvWinRate.text = "--"
            return
        }

        val avgScore = matches.map { it.totalScore }.average().toInt()
        val bestScore = matches.maxOf { it.totalScore }
        val winCount = matches.count { it.isVictory }
        val winRate = (winCount.toFloat() / matches.size * 100).toInt()

        binding.tvAvgScore.text = avgScore.toString()
        binding.tvBestScore.text = bestScore.toString()
        binding.tvWinRate.text = "${winRate}%"
    }

    private fun openReportPage(match: MatchEntity) {
        val intent = Intent(requireContext(), com.gameai.ui.GameReportActivity::class.java).apply {
            putExtra(com.gameai.ui.GameReportActivity.EXTRA_MATCH_ID, match.matchId)
            putExtra(com.gameai.ui.GameReportActivity.EXTRA_GAME_NAME, match.gameName)
            putExtra(com.gameai.ui.GameReportActivity.EXTRA_HERO_NAME, match.heroName)
            putExtra(com.gameai.ui.GameReportActivity.EXTRA_POSITION, match.position)
            putExtra(com.gameai.ui.GameReportActivity.EXTRA_START_TIME, match.startTime)
            putExtra(com.gameai.ui.GameReportActivity.EXTRA_END_TIME, match.endTime)
            putExtra(com.gameai.ui.GameReportActivity.EXTRA_TOTAL_SCORE, match.totalScore)
            putExtra(com.gameai.ui.GameReportActivity.EXTRA_GRADE, match.grade)
            putExtra(com.gameai.ui.GameReportActivity.EXTRA_KILLS, match.kdaKills)
            putExtra(com.gameai.ui.GameReportActivity.EXTRA_DEATHS, match.kdaDeaths)
            putExtra(com.gameai.ui.GameReportActivity.EXTRA_ASSISTS, match.kdaAssists)
            putExtra(com.gameai.ui.GameReportActivity.EXTRA_GPM, match.goldPerMin)
            putExtra(com.gameai.ui.GameReportActivity.EXTRA_DAMAGE, match.damageDealt)
            putExtra(com.gameai.ui.GameReportActivity.EXTRA_SCORE_JSON, match.scoreResultJson)
            putExtra(com.gameai.ui.GameReportActivity.EXTRA_IS_VICTORY, match.isVictory)
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /**
         * 获取等级对应的颜色（兼容中文等级和拉丁等级）
         */
        fun getGradeColor(grade: String): Int {
            return when {
                grade == "顶级" || grade == "S+" || grade == "S" -> 0xFFFFD700.toInt()  // 金色
                grade == "金牌" || grade == "A+" || grade == "A" -> 0xFFFF6347.toInt()  // 橙红
                grade == "银牌" || grade == "B+" || grade == "B" -> 0xFF4CAF50.toInt()  // 绿色
                grade == "铜牌" || grade == "C+" || grade == "C" -> 0xFF2196F3.toInt()  // 蓝色
                grade == "无评级" || grade == "D" || grade == "F" -> 0xFF9E9E9E.toInt() // 灰色
                else -> 0xFFCCCCCC.toInt()
            }
        }

        /**
         * 获取等级的简短显示形式
         */
        fun getGradeShortName(grade: String): String {
            return when (grade) {
                "顶级" -> "S"
                "金牌" -> "A"
                "银牌" -> "B"
                "铜牌" -> "C"
                "无评级" -> "D"
                else -> grade.take(1).uppercase()
            }
        }
    }
}
