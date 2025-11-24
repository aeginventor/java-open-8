package com.example.resilient_purchase.service

import com.example.resilient_purchase.repository.ProductRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service("noLockOrderService")
class NoLockOrderService(private val productRepository: ProductRepository) : OrderService {

    @Transactional
    override fun order(productId: Long, quantity: Int, method: String): Map<String, Any> {
        val product = productRepository.findById(productId).orElseThrow { IllegalArgumentException("product not found") }
        if (product.stock < quantity) throw IllegalStateException("insufficient stock")
        // 여기서 race condition 가능
        product.stock -= quantity
        productRepository.save(product)
        return mapOf("success" to true, "remainingStock" to product.stock, "method" to method)
    }

    override fun resetStock(productId: Long, stock: Int) {
        val p = productRepository.findById(productId).orElse(null)
        if (p != null) {
            p.stock = stock
            productRepository.save(p)
        }
    }

    override fun currentStock(productId: Long): Int {
        return productRepository.findById(productId).orElseThrow().stock
    }
}
