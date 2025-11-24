package com.example.resilient_purchase.service

import com.example.resilient_purchase.repository.ProductRepository
import org.springframework.stereotype.Service

@Service("lockOrderService")
class LockOrderService(private val productRepository: ProductRepository) : OrderService {

    @Synchronized
    override fun order(productId: Long, quantity: Int, method: String): Map<String, Any> {
        val product = productRepository.findById(productId)
            .orElseThrow { IllegalArgumentException("상품을 찾지 못했습니다.") }
        
        if (product.stock < quantity) {
            throw IllegalStateException("재고 부족")
        }
        
        // synchronized 블록으로 보호되어 race condition 방지
        product.stock -= quantity
        productRepository.save(product)
        
        return mapOf(
            "success" to true,
            "remainingStock" to product.stock,
            "method" to method
        )
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

