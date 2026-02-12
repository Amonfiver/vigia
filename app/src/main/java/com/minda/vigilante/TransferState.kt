package com.minda.vigilante

enum class TransferStatus { OK, OBSTACLE, FAULT_PENDING, FAULT_CONFIRMED }

class TransferState(
    val code: Int,
    private val confirmSeconds: Float = 15f
) {
    var status: TransferStatus = TransferStatus.OK
        private set

    private var faultStartMs: Long? = null
    private var faultNotified = false

    fun update(nowMs: Long, obstacle: Boolean, fault: Boolean): TransferStatus {
        // Obstáculo manda estado inmediato
        if (obstacle) {
            status = TransferStatus.OBSTACLE
            faultStartMs = null
            faultNotified = false
            return status
        }

        if (fault) {
            if (faultStartMs == null) faultStartMs = nowMs
            val elapsed = (nowMs - (faultStartMs ?: nowMs)) / 1000f
            status = if (elapsed >= confirmSeconds) TransferStatus.FAULT_CONFIRMED else TransferStatus.FAULT_PENDING
            return status
        }

        // OK (resetea)
        status = TransferStatus.OK
        faultStartMs = null
        faultNotified = false
        return status
    }

    fun remainingSeconds(nowMs: Long): Float? {
        if (status != TransferStatus.FAULT_PENDING) return null
        val start = faultStartMs ?: return null
        val elapsed = (nowMs - start) / 1000f
        return (confirmSeconds - elapsed).coerceAtLeast(0f)
    }

    fun shouldNotifyFaultConfirmed(): Boolean {
        if (status != TransferStatus.FAULT_CONFIRMED) return false
        if (faultNotified) return false
        faultNotified = true
        return true
    }

    fun shouldNotifyRecovered(prev: TransferStatus): Boolean {
        // Si antes estabas en fallo/obstáculo y vuelves a OK, avisamos
        return status == TransferStatus.OK &&
                (prev == TransferStatus.FAULT_CONFIRMED || prev == TransferStatus.FAULT_PENDING || prev == TransferStatus.OBSTACLE)
    }
}
