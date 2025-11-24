package com.example.resilient_purchase.dto

data class ConcurrencyExperimentRequest(
    val initialStock: Int = 100,
    val threads: Int = 200,
    val quantity: Int = 1,
    val method: String = "no-lock"
)
