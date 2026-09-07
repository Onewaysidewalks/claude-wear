package dev.claudewear.phone.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: PhoneViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            PhoneScreen(
                state = state,
                onSave = vm::save,
                onCheck = vm::checkConnection,
                onRefreshWatches = vm::refreshWatches,
                onPrompt = vm::setPrompt,
                onSend = vm::send,
                onCancel = vm::cancel,
                onDecide = vm::decide,
            )
        }
    }
}
