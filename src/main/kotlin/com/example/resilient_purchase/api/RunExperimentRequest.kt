package com.example.resilient_purchase.api

data class RunExperimentRequest(
    val threads: Int = 200,
    val method: String = "no-lock"
)

