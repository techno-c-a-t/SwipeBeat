package com.technocat.swipebeat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.technocat.swipebeat.databinding.LayoutHistorySheetBinding

class HistoryBottomSheetFragment : BottomSheetDialogFragment() {

    private var _binding: LayoutHistorySheetBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SortingViewModel by lazy {
        ViewModelProvider(requireActivity())[SortingViewModel::class.java]
    }

    private var showApproved = true
    private lateinit var adapter: HistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutHistorySheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupTabs()
        setupRecyclerView()
        setupSwipeHelper()
        updateData()
    }

    private fun setupTabs() {
        binding.tabApproved.setOnClickListener {
            showApproved = true
            binding.tabApproved.setBackgroundResource(R.drawable.bg_tab_selected)
            binding.tabApproved.setTextColor(resources.getColor(android.R.color.white, null))
            binding.tabRejected.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            binding.tabRejected.setTextColor(0xFF71717A.toInt())
            updateData()
        }

        binding.tabRejected.setOnClickListener {
            showApproved = false
            binding.tabRejected.setBackgroundResource(R.drawable.bg_tab_selected)
            binding.tabRejected.setTextColor(resources.getColor(android.R.color.white, null))
            binding.tabApproved.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            binding.tabApproved.setTextColor(0xFF71717A.toInt())
            updateData()
        }
    }

    private fun setupRecyclerView() {
        adapter = HistoryAdapter(
            onItemClick = { track ->
                viewModel.playTrackDirectly(track)
                dismiss()
            },
            onMoveClick = { track ->
                viewModel.toggleTrackDecision(track)
                updateData()
            },
            onRemoveClick = { track ->
                viewModel.removeTrackDecision(track)
                updateData()
            }
        )
        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistory.adapter = adapter
    }

    private fun updateData() {
        val approvedCount = viewModel.approvedTracks.size
        val rejectedCount = viewModel.rejectedTracks.size
        binding.tabApproved.text = "Одобрено ($approvedCount)"
        binding.tabRejected.text = "Отклонено ($rejectedCount)"

        val list = if (showApproved) viewModel.approvedTracks else viewModel.rejectedTracks
        adapter.submitList(list.reversed())
    }

    private fun setupSwipeHelper() {
        val swipeHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val track = adapter.getTrackAt(position) ?: return
                
                if (direction == ItemTouchHelper.LEFT) {
                    viewModel.toggleTrackDecision(track)
                } else if (direction == ItemTouchHelper.RIGHT) {
                    viewModel.removeTrackDecision(track)
                }
                updateData()
            }
        })
        swipeHelper.attachToRecyclerView(binding.rvHistory)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    class HistoryAdapter(
        private val onItemClick: (Track) -> Unit,
        private val onMoveClick: (Track) -> Unit,
        private val onRemoveClick: (Track) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {
        private var list = listOf<Track>()

        fun submitList(newList: List<Track>) {
            list = newList
            notifyDataSetChanged()
        }

        fun getTrackAt(position: Int): Track? {
            return list.getOrNull(position)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_track, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val track = list[position]
            holder.title.text = track.title
            holder.artist.text = track.artist
            holder.itemView.setOnClickListener { onItemClick(track) }
            
            holder.btnMove.setOnClickListener { onMoveClick(track) }
            holder.btnRemove.setOnClickListener { onRemoveClick(track) }

            holder.albumArt.tag = track.id
            holder.albumArt.visibility = View.GONE
            holder.fallback.visibility = View.VISIBLE

            val uri = track.uri
            Thread {
                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(holder.itemView.context, uri)
                    val artBytes = retriever.embeddedPicture
                    retriever.release()
                    if (artBytes != null) {
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                        holder.itemView.post {
                            if (holder.albumArt.tag == track.id) {
                                holder.albumArt.setImageBitmap(bitmap)
                                holder.albumArt.visibility = View.VISIBLE
                                holder.fallback.visibility = View.GONE
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }.start()
        }

        override fun getItemCount(): Int = list.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.tvHistoryTitle)
            val artist: TextView = view.findViewById(R.id.tvHistoryArtist)
            val albumArt: android.widget.ImageView = view.findViewById(R.id.ivHistoryArt)
            val fallback: TextView = view.findViewById(R.id.tvHistoryFallback)
            val btnMove: TextView = view.findViewById(R.id.btnHistoryMove)
            val btnRemove: TextView = view.findViewById(R.id.btnHistoryRemove)
        }
    }
}
