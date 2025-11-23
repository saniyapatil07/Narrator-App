package com.example.narratorapp.voice

sealed class VoiceCommand {
    object StartNavigation : VoiceCommand()
    object StopNavigation : VoiceCommand()
    object RecordWaypoint : VoiceCommand()
    object GetLocation : VoiceCommand()
    object EnableReadingMode : VoiceCommand()
    object DisableReadingMode : VoiceCommand()
    data class LearnFace(val name: String) : VoiceCommand()
    object LearnFacePrompt : VoiceCommand()
    data class LearnPlace(val name: String) : VoiceCommand()
    object LearnPlacePrompt : VoiceCommand()
    object RecognizeFace : VoiceCommand()
    object RecognizePlace : VoiceCommand()
    object DescribeScene : VoiceCommand()
    object FindObject : VoiceCommand()
    object IncreaseVolume : VoiceCommand()
    object DecreaseVolume : VoiceCommand()
    object Pause : VoiceCommand()
    object Resume : VoiceCommand()
    object Help : VoiceCommand()
    
    fun getDescription(): String {
        return when (this) {
            is StartNavigation -> "Start navigation"
            is StopNavigation -> "Stop navigation"
            is RecordWaypoint -> "Record waypoint"
            is GetLocation -> "Get location"
            is EnableReadingMode -> "Reading mode enabled"
            is DisableReadingMode -> "Normal mode enabled"
            is LearnFace -> "Learn face: $name"
            is LearnFacePrompt -> "Learn face"
            is LearnPlace -> "Learn place: $name"
            is LearnPlacePrompt -> "Learn place"
            is RecognizeFace -> "Recognize face"
            is RecognizePlace -> "Recognize place"
            is DescribeScene -> "Describe scene"
            is FindObject -> "Find object"
            is IncreaseVolume -> "Increase volume"
            is DecreaseVolume -> "Decrease volume"
            is Pause -> "Paused"
            is Resume -> "Resumed"
            is Help -> "Help"
        }
    }
}
