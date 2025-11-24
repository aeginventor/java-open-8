package com.example.resilient_purchase.api

data class LockConceptDemoResponse(
    val initialStock: Int,
    val localLockSuccessCount: Int,
    val localLockFinalStock: Int,
    val globalLockSuccessCount: Int,
    val globalLockFinalStock: Int
)

