package com.example.resilient_purchase.service

interface OrderService {
    fun order(productId: Long, quantity: Int, method: String): Map<String, Any>
    fun resetStock(productId: Long, stock: Int)
    fun currentStock(productId: Long): Int
}