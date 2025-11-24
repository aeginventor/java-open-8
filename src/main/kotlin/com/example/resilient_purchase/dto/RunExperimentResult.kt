package com.example.resilient_purchase.dto

data class RunExperimentResult(
    val method: String,
    val initialStock: Int,
    val threads: Int,
    val successCount: Int,
    val failureCount: Int,
    val remainingStock: Int,
    val expectedDecrease: Int,
    val actualDecrease: Int,
    val hasGhostSuccess: Boolean
)

