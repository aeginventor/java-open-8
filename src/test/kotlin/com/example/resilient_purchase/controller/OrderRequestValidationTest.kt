package com.example.resilient_purchase.controller

import com.example.resilient_purchase.dto.OrderRequest
import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OrderRequestValidationTest {

    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `productId가 없으면 검증에 실패해야 한다`() {
        // given
        val req = OrderRequest(
            productId = null,
            quantity = 1,
            method = "lock"
        )

        // when
        val violations = validator.validate(req)

        // then
        assertEquals(1, violations.size)
        val violation = violations.first()
        assertEquals("productId", violation.propertyPath.toString())
    }

    @Test
    fun `quantity가 1 미만이면 검증에 실패해야 한다`() {
        // given
        val req = OrderRequest(
            productId = 1L,
            quantity = 0,
            method = "lock"
        )

        // when
        val violations = validator.validate(req)

        // then
        assertTrue(violations.any { it.propertyPath.toString() == "quantity" })
    }
}
