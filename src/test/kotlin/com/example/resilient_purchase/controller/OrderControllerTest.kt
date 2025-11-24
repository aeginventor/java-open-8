package com.example.resilient_purchase.controller

import com.example.resilient_purchase.domain.Product
import com.example.resilient_purchase.repository.ProductRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerTest(

    @Autowired val mockMvc: MockMvc,
    @Autowired val productRepository: ProductRepository
) {

    @Test
    fun `주문 API 정상 요청 시 성공 응답을 반환해야 한다`() {
        // given
        val product = productRepository.save(Product(name = "api-test", stock = 10))

        val json = """
            {
              "productId": ${product.id},
              "quantity": 1,
              "method": "lock"
            }
        """.trimIndent()

        // when & then
        mockMvc.perform(
            post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
    }

    @Test
    fun `productId 없이 요청하면 400과 에러 응답을 반환해야 한다`() {
        // given
        val json = """
        {
          "quantity": 1,
          "method": "lock"
        }
    """.trimIndent()

        // when & then
        mockMvc.perform(
            post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
    }

    @Test
    fun `재고보다 많은 수량을 주문하면 BAD_REQUEST 에러를 반환해야 한다`() {
        // given
        val product = productRepository.save(Product(name = "no-stock", stock = 0))

        val json = """
        {
          "productId": ${product.id},
          "quantity": 1,
          "method": "lock"
        }
    """.trimIndent()

        // when & then
        mockMvc.perform(
            post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
    }
}
