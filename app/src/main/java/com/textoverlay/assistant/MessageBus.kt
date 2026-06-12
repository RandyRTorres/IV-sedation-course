package com.textoverlay.assistant

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Tiny in-process pub/sub so the [MessageNotificationListener] can hand newly
 * captured messages to the [OverlayService] without a bound-service dance.
 */
object MessageBus {
    private val _messages = MutableSharedFlow<IncomingMessage>(
        replay = 1,
        extraBufferCapacity = 16
    )
    val messages: SharedFlow<IncomingMessage> = _messages.asSharedFlow()

    fun publish(message: IncomingMessage) {
        _messages.tryEmit(message)
    }
}
