package com.example.resilient_purchase.api

data class ConcurrencyExperimentResult(
    val method: String,
    val initialStock: Int,
    val threads: Int,
    val quantity: Int,
    val successCount: Int,
    val failureCount: Int,
    val remainingStock: Int,
    val invariantHolds: Boolean
)
