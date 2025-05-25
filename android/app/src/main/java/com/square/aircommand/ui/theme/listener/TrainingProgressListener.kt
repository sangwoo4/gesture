package com.square.aircommand.ui.theme.listener

interface TrainingProgressListener {
    fun onCollectionProgress(percent: Int) {}
    fun onTrainingStarted() {}
    fun onModelDownloadStarted() {}
    fun onModelDownloadComplete() {}
}