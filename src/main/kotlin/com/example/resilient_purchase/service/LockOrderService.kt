package com.example.resilient_purchase.service

import com.example.resilient_purchase.repository.ProductRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Service("lockOrderService")
class LockOrderService(
    private val productRepository: ProductRepository
) : OrderService {

    private val lock = ReentrantLock()

    @Transactional
    override fun order(productId: Long, quantity: Int, method: String): Map<String, Any> {
        return lock.withLock {
            val product = productRepository.findById(productId)
                .orElseThrow { IllegalArgumentException("product not found") }

            if (product.stock < quantity) {
                throw IllegalStateException("insufficient stock")
            }

            product.stock -= quantity
            productRepository.save(product)

            mapOf(
                "success" to true,
                "remainingStock" to product.stock,
                "method" to method
            )
        }
    }

    override fun resetStock(productId: Long, stock: Int) {
        lock.withLock {
            val p = productRepository.findById(productId).orElse(null)
            if (p != null) {
                p.stock = stock
                productRepository.save(p)
            }
        }
    }

    override fun currentStock(productId: Long): Int {
        return lock.withLock {
            productRepository.findById(productId).orElseThrow().stock
        }
    }
}
