package com.b3rnhard.steprecorder

import com.bitwig.extension.controller.api.ClipLauncherSlot
import com.bitwig.extension.controller.api.ClipLauncherSlotBank
import com.bitwig.extension.controller.api.TrackBank

class BankUtil(val trackBank: TrackBank) {
    fun forEachClipLauncherSlot(callback: (ClipLauncherSlot, Int, Int) -> Unit) {
        for (i in 0..<trackBank.sizeOfBank - 1) {
            val trackIndex = i
            val track = trackBank.getItemAt(trackIndex)
            val slotBank = track.clipLauncherSlotBank()
            for (j in 0..slotBank.sizeOfBank - 1) {
                val slot = slotBank.getItemAt(j)
                callback(slot, i, j)
            }
        }
    }

    fun forEachBank(callback: (ClipLauncherSlotBank, Int) -> Unit) {
        for (i in 0..<trackBank.sizeOfBank - 1) {
            val trackIndex = i
            val track = trackBank.getItemAt(trackIndex)
            val slotBank = track.clipLauncherSlotBank()
            callback(slotBank, i)
        }

    }
}