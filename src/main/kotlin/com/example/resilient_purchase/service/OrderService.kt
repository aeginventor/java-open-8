package com.example.resilient_purchase.service

interface OrderService {
    fun order(productId: Long, quantity: Int, method: String): OrderResponse
    fun resetStock(productId: Long, stock: Int)
    fun currentStock(productId: Long): Int
}