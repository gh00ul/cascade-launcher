package com.gh00ul.cascade.testing

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Stands in for the theme's ripple (provide it as LocalIndication, under the theme): it hears every press the way the
 * ripple would, through the clickable's own interaction source, and keeps the ones not yet released or cancelled.
 */
class PressRecorder : IndicationNodeFactory {
    /** Presses under way, as the ripple sees them: while one is here, a real ripple would still be drawing it. */
    val held = ArrayList<PressInteraction.Press>()

    /** Every press seen. */
    var presses = 0
        private set

    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {
        override fun onAttach() {
            // Undispatched, as the row's own listener: clickable builds this node for the press it then sends.
            coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                interactionSource.interactions.collect {
                    when (it) {
                        is PressInteraction.Press -> {
                            presses++
                            held += it
                        }
                        is PressInteraction.Release -> held -= it.press
                        is PressInteraction.Cancel -> held -= it.press
                    }
                }
            }
        }
    }

    override fun equals(other: Any?) = other === this
    override fun hashCode() = System.identityHashCode(this)
}
