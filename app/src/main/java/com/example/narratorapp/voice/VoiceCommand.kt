package com.example.narratorapp.voice

/**
 * Sealed class representing all possible voice commands
 */
sealed class VoiceCommand {
    
    // Navigation Commands
    object StartNavigation : VoiceCommand()
    object StopNavigation : VoiceCommand()
    object RecordWaypoint : VoiceCommand()
    object GetLocation : VoiceCommand()
    
    // Reading Mode
    object EnableReadingMode : VoiceCommand()
    object DisableReadingMode : VoiceCommand()
    
    // Memory/Recognition Commands
    data class LearnFace(val name: String) : VoiceCommand()
    object LearnFacePrompt : VoiceCommand() // When name not provided
    data class LearnPlace(val name: String) : VoiceCommand()
    object LearnPlacePrompt : VoiceCommand()
    object RecognizeFace : VoiceCommand()
    object RecognizePlace : VoiceCommand()
    
    // Scene Description
    object DescribeScene : VoiceCommand()
    object FindObject : VoiceCommand()
    
    // Volume Control
    object IncreaseVolume : VoiceCommand()
    object DecreaseVolume : VoiceCommand()
    
    // Playback Control
    object Pause : VoiceCommand()
    object Resume : VoiceCommand()
    
    // Help
    object Help : VoiceCommand()
    
    /**
     * Get user-friendly description of the command
     */
    fun getDescription(): String {
        return when (this) {
            is StartNavigation -> "Start navigation to destination"
            is StopNavigation -> "Stop current navigation"
            is RecordWaypoint -> "Record current location as waypoint"
            is GetLocation -> "Announce current location"
            is EnableReadingMode -> "Switch to reading mode for text"
            is DisableReadingMode -> "Return to normal mode"
            is LearnFace -> "Learn face with name: $name"
            is LearnFacePrompt -> "Learn a new face"
            is LearnPlace -> "Learn place with name: $name"
            is LearnPlacePrompt -> "Learn a new place"
            is RecognizeFace -> "Recognize person in view"
            is RecognizePlace -> "Recognize current location"
            is DescribeScene -> "Describe what's in view"
            is FindObject -> "Search for specific object"
            is IncreaseVolume -> "Increase narration volume"
            is DecreaseVolume -> "Decrease narration volume"
            is Pause -> "Pause narration"
            is Resume -> "Resume narration"
            is Help -> "Show available commands"
        }
    }
}