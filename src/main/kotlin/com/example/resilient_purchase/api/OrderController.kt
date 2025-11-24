package com.example.resilient_purchase.api

import com.example.resilient_purchase.dto.OrderRequest
import com.example.resilient_purchase.service.OrderServiceSelector
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/orders")
class OrderController(
    private val orderServiceSelector: OrderServiceSelector
) {

    @PostMapping
    fun order(@Valid @RequestBody req: OrderRequest): ResponseEntity<Any> {
        val service = orderServiceSelector.select(req.method ?: "lock")
        val result = service.order(req.productId!!, req.quantity, req.method ?: "lock")
        return ResponseEntity.ok(result)
    }
}
