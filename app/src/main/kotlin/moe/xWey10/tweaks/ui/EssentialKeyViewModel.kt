// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 xWey10

package moe.xWey10.tweaks.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import moe.xWey10.tweaks.features.essentialkey.EssentialKeyAction
import moe.xWey10.tweaks.features.essentialkey.EssentialKeyResult
import java.util.concurrent.Executors

internal class EssentialKeyViewModel(application: Application) : AndroidViewModel(application) {
    enum class Operation { FREE, RESTORE }
    data class State(
        val running: Boolean = false,
        val result: EssentialKeyResult? = null,
        val operation: Operation = Operation.FREE,
    )

    private val action = EssentialKeyAction(application)
    private val worker = Executors.newSingleThreadExecutor()
    private val mutableState = MutableLiveData(State())
    val state: LiveData<State> = mutableState

    fun freeKey() = submit(Operation.FREE)

    fun restoreKey() = submit(Operation.RESTORE)

    private fun submit(operation: Operation) {
        if (mutableState.value?.running == true) return
        mutableState.value = State(running = true, operation = operation)
        worker.execute {
            val result = try {
                if (operation == Operation.RESTORE) action.restore() else action.run()
            } catch (_: Exception) {
                EssentialKeyResult.FAILED
            }
            mutableState.postValue(State(result = result, operation = operation))
        }
    }

    override fun onCleared() {
        worker.shutdown()
    }
}
