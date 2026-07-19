package com.technocat.slidebit

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

data class SwipeAction(
    val track: Track,
    val isApproved: Boolean,
    val queueIndex: Int
)

class SortingViewModel : ViewModel() {

    val swipeHistory = mutableListOf<SwipeAction>()

    private val _currentTrack = MutableLiveData<Track?>()
    val currentTrack: LiveData<Track?> = _currentTrack

    private val _currentIndex = MutableLiveData<Int>(0)
    val currentIndex: LiveData<Int> = _currentIndex

    private val _totalTracks = MutableLiveData<Int>(0)
    val totalTracks: LiveData<Int> = _totalTracks

    private val _isPlaying = MutableLiveData<Boolean>(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    val trackQueue = mutableListOf<Track>()
    val approvedTracks = mutableListOf<Track>()
    val rejectedTracks = mutableListOf<Track>()

    val selectedSources = mutableListOf<SelectedSource>()
    var selectedLogicMode = LogicMode.UNION

    fun combineSelectedSources() {
        val sources = selectedSources
        val mode = selectedLogicMode

        if (sources.isEmpty()) {
            trackQueue.clear()
            _totalTracks.value = 0
            return
        }

        val positiveSources = sources.filter { !it.isExcluded }
        val negativeSources = sources.filter { it.isExcluded }

        val positiveTracks = mutableListOf<Track>()

        if (positiveSources.isNotEmpty()) {
            if (positiveSources.size == 1) {
                positiveTracks.addAll(positiveSources[0].tracks)
            } else {
                val keyToSourcesMap = mutableMapOf<String, MutableSet<Int>>()
                val keyToTrackMap = mutableMapOf<String, Track>()

                for (sourceIndex in positiveSources.indices) {
                    for (track in positiveSources[sourceIndex].tracks) {
                        val key = track.getMatchKey()
                        keyToSourcesMap.getOrPut(key) { mutableSetOf() }.add(sourceIndex)
                        if (!keyToTrackMap.containsKey(key)) {
                            keyToTrackMap[key] = track
                        }
                    }
                }

                when (mode) {
                    LogicMode.UNION -> {
                        for (key in keyToTrackMap.keys) {
                            keyToTrackMap[key]?.let { positiveTracks.add(it) }
                        }
                    }
                    LogicMode.DUP -> {
                        for ((key, sourceIndices) in keyToSourcesMap) {
                            if (sourceIndices.size >= 2) {
                                keyToTrackMap[key]?.let { positiveTracks.add(it) }
                            }
                        }
                    }
                    LogicMode.UNIQUE -> {
                        for ((key, sourceIndices) in keyToSourcesMap) {
                            if (sourceIndices.size == 1) {
                                keyToTrackMap[key]?.let { positiveTracks.add(it) }
                            }
                        }
                    }
                }
            }
        }

        if (negativeSources.isNotEmpty()) {
            val negativeKeys = mutableSetOf<String>()
            for (negSource in negativeSources) {
                for (track in negSource.tracks) {
                    negativeKeys.add(track.getMatchKey())
                }
            }
            positiveTracks.removeAll { negativeKeys.contains(it.getMatchKey()) }
        }

        // Apply blacklist folder filtering
        val blacklist = settings.blacklistedFolders
        if (blacklist.isNotEmpty()) {
            positiveTracks.removeAll { track ->
                blacklist.any { folder ->
                    folder.isNotEmpty() && track.filePath.contains(folder, ignoreCase = true)
                }
            }
        }

        trackQueue.clear()
        trackQueue.addAll(positiveTracks)
        _totalTracks.value = trackQueue.size
    }

    // Settings
    val settings = SettingsState()

    fun initSession(tracks: List<Track>) {
        val tracksCopy = tracks.toList()
        trackQueue.clear()
        trackQueue.addAll(tracksCopy)
        approvedTracks.clear()
        rejectedTracks.clear()

        _totalTracks.value = trackQueue.size
        _currentIndex.value = 0

        if (trackQueue.isNotEmpty()) {
            _currentTrack.value = trackQueue[0]
            _isPlaying.value = settings.isAutoplayEnabled
        } else {
            _currentTrack.value = null
            _isPlaying.value = false
        }
    }

    fun resumeSession(queue: List<Track>, approved: List<Track>, rejected: List<Track>, index: Int) {
        trackQueue.clear()
        trackQueue.addAll(queue)
        approvedTracks.clear()
        approvedTracks.addAll(approved)
        rejectedTracks.clear()
        rejectedTracks.addAll(rejected)

        _totalTracks.value = trackQueue.size
        setCurrentIndexAndTrack(index)
    }

    fun togglePlayPause() {
        if (_currentTrack.value != null) {
            _isPlaying.value = !(_isPlaying.value ?: false)
        }
    }

    fun setPlaying(playing: Boolean) {
        _isPlaying.value = playing
    }

    fun onSwipeUp() {
        val current = _currentTrack.value ?: return
        val index = _currentIndex.value ?: 0
        swipeHistory.add(SwipeAction(current, isApproved = true, queueIndex = index))
        if (!approvedTracks.contains(current)) {
            approvedTracks.add(current)
        }
        moveToNextTrack()
    }

    fun onSwipeDown() {
        val current = _currentTrack.value ?: return
        val index = _currentIndex.value ?: 0
        swipeHistory.add(SwipeAction(current, isApproved = false, queueIndex = index))
        if (!rejectedTracks.contains(current)) {
            rejectedTracks.add(current)
        }
        moveToNextTrack()
    }

    fun undo() {
        if (swipeHistory.isEmpty()) return
        val lastAction = swipeHistory.removeAt(swipeHistory.size - 1)
        if (lastAction.isApproved) {
            approvedTracks.remove(lastAction.track)
        } else {
            rejectedTracks.remove(lastAction.track)
        }
        _currentIndex.value = lastAction.queueIndex
        _currentTrack.value = lastAction.track
    }

    private fun moveToNextTrack() {
        val curr = _currentIndex.value ?: 0
        var foundIndex = -1
        
        // 1. Search forward from curr + 1
        for (i in (curr + 1) until trackQueue.size) {
            val candidate = trackQueue[i]
            if (!approvedTracks.contains(candidate) && !rejectedTracks.contains(candidate)) {
                foundIndex = i
                break
            }
        }
        
        // 2. If not found, wrap around and search from 0 to curr - 1
        if (foundIndex == -1) {
            for (i in 0 until curr) {
                val candidate = trackQueue[i]
                if (!approvedTracks.contains(candidate) && !rejectedTracks.contains(candidate)) {
                    foundIndex = i
                    break
                }
            }
        }
        
        if (foundIndex != -1) {
            _currentIndex.value = foundIndex
            _currentTrack.value = trackQueue[foundIndex]
            _isPlaying.value = settings.isAutoplayEnabled
        } else {
            // No unprocessed tracks left!
            _currentIndex.value = trackQueue.size
            _currentTrack.value = null
            _isPlaying.value = false
        }
    }

    // History interaction methods
    fun playTrackDirectly(track: Track) {
        val index = trackQueue.indexOf(track)
        if (index != -1) {
            _currentIndex.value = index
            _currentTrack.value = track
            _isPlaying.value = true
        }
    }

    fun toggleTrackDecision(track: Track) {
        if (approvedTracks.contains(track)) {
            approvedTracks.remove(track)
            rejectedTracks.add(track)
        } else if (rejectedTracks.contains(track)) {
            rejectedTracks.remove(track)
            approvedTracks.add(track)
        }
    }

    fun removeTrackDecision(track: Track) {
        approvedTracks.remove(track)
        rejectedTracks.remove(track)
        
        // Put it back in the queue at the current index so they see it immediately
        val curIndex = _currentIndex.value ?: 0
        if (!trackQueue.contains(track)) {
            trackQueue.add(curIndex, track)
        } else {
            // If already exists, move it to the current index
            trackQueue.remove(track)
            trackQueue.add(curIndex, track)
        }
        _totalTracks.value = trackQueue.size
        _currentTrack.value = track
        _isPlaying.value = true
    }

    fun setCurrentIndexAndTrack(index: Int) {
        if (index >= 0 && index < trackQueue.size) {
            _currentIndex.value = index
            _currentTrack.value = trackQueue[index]
            _isPlaying.value = settings.isAutoplayEnabled
        } else {
            _currentIndex.value = index
            _currentTrack.value = null
            _isPlaying.value = false
        }
    }
}
