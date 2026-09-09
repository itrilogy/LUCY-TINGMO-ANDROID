package lab.luxi.tingmo.ui.navigation

sealed class Route(val path: String, val label: String) {
    data object Transcribe : Route("transcribe", "撰写")
    data object Recordings : Route("recordings", "录音")
    data object Models : Route("models", "模型")
    data object Corrections : Route("corrections", "纠偏")
    data object Ai : Route("ai", "智能")
    data object About : Route("about", "关于")

    companion object {
        val bottom = listOf(Transcribe, Recordings, Models, Corrections, Ai)
    }
}
