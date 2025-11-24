package com.example.resilient_purchase.api

import com.example.resilient_purchase.repository.ProductRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/products")
class ProductController(
    private val productRepository: ProductRepository
) {

    @GetMapping("/{id}")
    fun getProduct(@PathVariable id: Long): ResponseEntity<Any> {
        val product = productRepository.findById(id)
            .orElseThrow { IllegalArgumentException("상품을 찾지 못했습니다.") }

        val body = mapOf(
            "id" to product.id,
            "name" to product.name,
            "stock" to product.stock
        )
        return ResponseEntity.ok(body)
    }
}
