package com.example.resilient_purchase.service

data class OrderResponse(
    val success: Boolean,
    val remainingStock: Int,
    val method: String
)

