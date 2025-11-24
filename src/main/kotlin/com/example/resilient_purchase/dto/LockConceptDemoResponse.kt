package com.example.resilient_purchase.dto

data class LockConceptDemoResponse(
    val initialStock: Int,
    val localLockSuccessCount: Int,
    val localLockFinalStock: Int,
    val globalLockSuccessCount: Int,
    val globalLockFinalStock: Int
)

