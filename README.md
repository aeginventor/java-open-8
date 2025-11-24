# 동시성 안전 구매 API
> Kotlin + Spring Boot 기반 재고 감소 API 구현

## 1. 프로젝트 개요

본 프로젝트는 **주문/구매 과정에서 발생하는 동시성 문제**를 직접 재현하고,  
이를 **Kotlin + Spring Boot** 기반의 재고 관리 API와 실험용 웹 UI로 탐구하는 것을 목표로 합니다.

실제 서비스에서는 다음과 같은 문제가 자주 발생합니다.

- 한정 수량 상품에 여러 사용자가 동시에 요청을 보낼 때
- 재고가 음수가 되거나, **재고보다 많이 판 것처럼 보이는 상태**가 발생하는 문제
- 동시성 버그가 로그나 모니터링 없이 조용히 쌓였다가 장애로 이어지는 문제

이 프로젝트에서는 다음을 경험합니다.

- 단순한 재고 차감 로직으로 **동시성 이슈를 직접 재현**해 보고
- 서로 다른 동시성 제어 전략을 적용해 **응답과 실제 재고가 어떻게 달라지는지** 확인합니다.
- 이 과정에서 **Kotlin 문법**, **Spring Boot 구조**, **테스트 코드**를 함께 익힙니다.

---

## 2. 프로젝트 구조

### 2.1 전체 아키텍처

```
resilient-purchase
├── api/               # REST 컨트롤러 계층
├── dto/               # 요청/응답 데이터 전송 객체
├── service/           # 비즈니스 로직 및 동시성 제어
├── domain/            # 도메인 엔티티
├── repository/        # 데이터 접근 계층
└── demo/              # 락 개념 데모용 클래스
```

### 2.2 상세 패키지 구조

#### **main/kotlin/com.example.resilient_purchase**

```
├── api/
│   ├── OrderController.kt                      # 주문 API
│   ├── ProductController.kt                    # 상품 조회 API
│   ├── UiController.kt                         # 웹 UI용 실험 API
│   ├── ConcurrencyExperimentController.kt      # 동시성 실험 API
│   └── GlobalExceptionHandler.kt               # 전역 예외 처리
│
├── dto/
│   ├── OrderRequest.kt                         # 주문 요청
│   ├── ConcurrencyExperimentRequest.kt         # 실험 요청
│   ├── ConcurrencyExperimentResult.kt          # 실험 결과
│   ├── StockResponse.kt                        # 재고 응답
│   ├── RunExperimentRequest.kt                 # UI 실험 요청
│   ├── RunExperimentResult.kt                  # UI 실험 결과
│   ├── ResetStockRequest.kt                    # 재고 리셋 요청
│   └── LockConceptDemoResponse.kt              # 락 데모 응답
│
├── service/
│   ├── OrderService.kt                         # 주문 서비스 인터페이스
│   ├── NoLockOrderService.kt                   # 락 없는 구현 (동시성 문제 재현용)
│   ├── LockOrderService.kt                     # @Synchronized 기반 로컬 락
│   ├── PessimisticLockOrderService.kt          # DB 비관적 락
│   ├── OrderServiceSelector.kt                 # 락 전략 선택
│   ├── ConcurrencyTestExecutor.kt              # 동시성 테스트 실행기
│   └── OrderResponse.kt                        # 주문 응답 DTO
│
├── domain/
│   └── Product.kt                              # 상품 엔티티 (id, name, stock)
│
├── repository/
│   └── ProductRepository.kt                    # 상품 저장소 (JPA)
│
└── demo/
    ├── DemoSharedStock.kt                      # 데모용 공유 재고
    ├── LocalLockDemoService.kt                 # 로컬 락 데모
    └── GlobalLockDemoService.kt                # 글로벌 락 데모
```

#### **test/kotlin/com.example.resilient_purchase**

```
├── controller/                                 # 컨트롤러 테스트
│   ├── OrderControllerTest.kt                  # API 통합 테스트
│   └── OrderRequestValidationTest.kt           # DTO 유효성 검증
│
├── concurrency/                                # 동시성 테스트
│   ├── NoLockConcurrentTest.kt                 # 동시성 문제 재현 테스트
│   ├── LockConcurrentTest.kt                   # 로컬 락 검증
│   └── PessimisticLockConcurrentTest.kt        # DB 락 검증
│
└── fixture/
    └── TestFixture.kt                          # 테스트 공통 유틸리티
```

### 2.3 주요 클래스 책임

| 계층 | 클래스 | 책임 |
|------|--------|------|
| **API** | OrderController | 주문 요청 처리 및 응답 |
| | UiController | 웹 UI 실험 기능 제공 |
| **Service** | OrderService | 주문 비즈니스 로직 인터페이스 |
| | NoLockOrderService | 동시성 문제 재현 (락 없음) |
| | LockOrderService | JVM 레벨 동기화 (@Synchronized) |
| | PessimisticLockOrderService | DB 레벨 동기화 (비관적 락) |
| | OrderServiceSelector | 락 전략 선택 |
| | ConcurrencyTestExecutor | 동시성 테스트 실행 |
| **Domain** | Product | 상품 엔티티 (재고 관리) |
| **Repository** | ProductRepository | 상품 데이터 접근 |

---

## 3. 구현 상세

### 3.1 동시성 시나리오

- 하나의 `Product` 엔티티가 있고, `stock` 필드로 재고를 관리합니다.
- 여러 스레드(또는 여러 HTTP 요청)가 동시에 "재고 감소" 요청을 보냅니다.
- 이 때, 구현 방식에 따라 다음과 같은 현상이 발생하거나 방지됩니다.
  - 같은 재고를 두 번 이상 차감
  - 성공 응답을 두 번 보내 놓고 실제 재고는 한 번만 줄어드는 상황
  - 재고가 0 이하(음수)로 떨어지는 상황

### 3.2 동시성 제어 전략

현재는 **세 가지 전략**을 구현했습니다.

#### 1. **NoLockOrderService (락 없음, baseline)**
- 아무런 동기화 없이 단순히 재고를 조회하고 차감하는 서비스
- 멀티스레드 환경에서 **응답과 재고가 어긋나는** 문제가 발생할 수 있습니다.
- 목적: **"동시성 버그를 재현하는 용도"**

```kotlin
@Transactional
override fun order(productId: Long, quantity: Int, method: String): OrderResponse {
    val product = productRepository.findById(productId).orElseThrow { ... }
    if (product.stock < quantity) throw IllegalStateException("재고 부족")
    // ⚠️ 여기서 race condition 가능
    product.stock -= quantity
    productRepository.save(product)
    return OrderResponse(success = true, remainingStock = product.stock, method = method)
}
```

#### 2. **LockOrderService (`@Synchronized` 기반, 로컬 락)**
- Kotlin의 `@Synchronized`를 사용해 `order()` 메서드 전체를 직렬화합니다.
- 한 번에 하나의 스레드만 주문 로직을 수행할 수 있도록 **JVM 내부에서** 동기화합니다.
- 단일 인스턴스 환경에서는 동시성 문제를 대부분 막을 수 있지만,  
  인스턴스가 여러 대인 환경에서는 각 인스턴스마다 락이 따로 존재한다는 한계가 있습니다.

```kotlin
@Synchronized
override fun order(productId: Long, quantity: Int, method: String): OrderResponse {
    // synchronized 블록으로 보호되어 race condition 방지
    ...
}
```

#### 3. **PessimisticLockOrderService (DB 비관적 락 기반)**
- JPA의 `@Lock(LockModeType.PESSIMISTIC_WRITE)`를 사용하여  
  **데이터베이스 레벨에서 행(row)에 락을 거는 방식**입니다.
- 같은 상품 행을 갱신하려는 트랜잭션끼리는 DB에서 순서를 보장합니다.
- 여러 인스턴스가 같은 DB를 사용해도, **DB 차원에서 동시성을 제어**할 수 있습니다.

```kotlin
// Repository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select p from Product p where p.id = :id")
fun findByIdForUpdate(@Param("id") id: Long): Product?
```

> 세 서비스는 모두 `OrderService` 인터페이스를 구현하며,  
> `OrderRequest.method` 값(`"no-lock"`, `"lock"`, `"pessimistic"`)에 따라 `OrderServiceSelector`가 적절한 전략을 선택합니다.

---

## 4. API 명세

### 4.1 주문 생성 API

#### `POST /orders`

주문을 생성하고 재고를 차감합니다.

**Request**

```json
{
  "productId": 1,
  "quantity": 1,
  "method": "lock"
}
```

| 필드 | 타입 | 필수 | 설명 | 기본값 |
|------|------|------|------|--------|
| productId | Long | ✅ | 주문할 상품 ID | - |
| quantity | Int | ✅ | 주문 수량 (최소 1) | 1 |
| method | String | ❌ | 락 전략 선택 | "lock" |

**method 값**
- `"no-lock"`: 락 없음 (동시성 문제 재현)
- `"lock"`: @Synchronized 로컬 락
- `"pessimistic"`: DB 비관적 락

**Response (성공)**

```json
{
  "success": true,
  "remainingStock": 97,
  "method": "lock"
}
```

**Response (실패 - 재고 부족)**

```json
{
  "success": false,
  "error": "BAD_REQUEST",
  "detail": "재고 부족"
}
```

**Response (실패 - 유효성 검증)**

```json
{
  "success": false,
  "error": "VALIDATION_ERROR",
  "detail": "productId는 필수입니다."
}
```

**Status Codes**
- `200 OK`: 주문 성공
- `400 Bad Request`: 유효성 검증 실패 또는 재고 부족

---

### 4.2 상품 재고 조회 API

#### `GET /products/{id}`

특정 상품의 현재 재고를 조회합니다.

**Response**

```json
{
  "id": 1,
  "name": "sample-product",
  "stock": 80
}
```

**Status Codes**
- `200 OK`: 조회 성공
- `500 Internal Server Error`: 상품을 찾을 수 없음

---

### 4.3 동시성 실험 API

#### `POST /experiments/concurrency`

동시성 테스트를 실행하고 결과를 반환합니다.

**Request**

```json
{
  "initialStock": 100,
  "threads": 200,
  "quantity": 1,
  "method": "no-lock"
}
```

| 필드 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| initialStock | Int | 100 | 실험 시작 재고 |
| threads | Int | 200 | 동시 요청 수 |
| quantity | Int | 1 | 요청당 주문 수량 |
| method | String | "no-lock" | 락 전략 |

**Response**

```json
{
  "method": "no-lock",
  "initialStock": 100,
  "threads": 200,
  "quantity": 1,
  "successCount": 150,
  "failureCount": 50,
  "remainingStock": 74,
  "invariantHolds": false
}
```

| 필드 | 설명 |
|------|------|
| invariantHolds | 불변식 만족 여부: `remainingStock >= 0 && successCount * quantity + remainingStock == initialStock` |

---

### 4.4 웹 UI 전용 API

웹 브라우저에서 실험을 수행하기 위한 API입니다.

#### `POST /ui/reset-stock`

실험 대상 상품(ID=1)의 재고를 설정합니다.

**Request**

```json
{
  "stock": 100
}
```

**Response**

```json
{
  "productId": 1,
  "stock": 100
}
```

---

#### `GET /ui/current-stock`

현재 실험 대상 상품의 재고를 조회합니다.

**Response**

```json
{
  "productId": 1,
  "stock": 95
}
```

---

#### `POST /ui/run-experiment`

웹 UI에서 동시성 실험을 실행합니다.

**Request**

```json
{
  "threads": 200,
  "method": "no-lock"
}
```

**Response**

```json
{
  "method": "no-lock",
  "initialStock": 100,
  "threads": 200,
  "successCount": 150,
  "failureCount": 50,
  "remainingStock": 74,
  "expectedDecrease": 150,
  "actualDecrease": 26,
  "hasGhostSuccess": true
}
```

| 필드 | 설명 |
|------|------|
| expectedDecrease | 성공 응답 기준 기대 감소량 (`successCount * 1`) |
| actualDecrease | 실제 재고 감소량 (`initialStock - remainingStock`) |
| hasGhostSuccess | "재고에 반영되지 않은 성공 응답" 존재 여부 |

> `hasGhostSuccess = true`는 심각한 동시성 문제를 의미합니다.  
> 사용자에게는 성공이라고 응답했지만 실제로는 재고가 차감되지 않은 상태입니다.

---

#### `GET /ui/lock-concept-demo`

로컬 락과 글로벌 락의 차이를 시연합니다.

**Response**

```json
{
  "initialStock": 1,
  "localLockSuccessCount": 2,
  "localLockFinalStock": -1,
  "globalLockSuccessCount": 1,
  "globalLockFinalStock": 0
}
```

---

## 5. 실행 방법

### 5.1 빌드 및 실행

```bash
./gradlew clean build
./gradlew bootRun
```

서버는 기본적으로 `http://localhost:8080` 에서 실행됩니다.

### 5.2 초기 데이터

애플리케이션 실행 시 `data.sql`을 통해 기본 상품이 자동으로 삽입됩니다.

```sql
INSERT INTO product (id, name, stock) VALUES (1, 'sample-product', 100);
ALTER TABLE product ALTER COLUMN id RESTART WITH 100;
```

이를 위해 `application.properties`에 다음 설정을 추가했습니다.

```properties
spring.jpa.defer-datasource-initialization=true
```

이 설정과 `data.sql` 덕분에, 별도 API를 호출하지 않아도 **ID=1인 상품**에 대해 바로 실험을 진행할 수 있습니다.

---

## 6. 테스트 구성

테스트는 **동시성 위험이 큰 영역**과 **입력/컨트롤러 계층**에 집중해 구성했습니다.

### 6.1 패키지 구조

```
test/
├── controller/          # API 계층 테스트
├── concurrency/         # 동시성 테스트
└── fixture/             # 테스트 공통 유틸리티
```

### 6.2 동시성 테스트 (concurrency/)

#### `NoLockConcurrentTest`
- `NoLockOrderService`에서 멀티스레드 주문을 수행해,
  재고가 비정상적으로 변경되거나,  
  **성공 응답 건수와 실제 재고 감소량이 어긋날 수 있음을** 재현합니다.

```kotlin
@Test
fun `no-lock 동시성 오버셀 재현 테스트`() {
    val product = createProduct()
    val service = getNoLockOrderService()

    TestFixture.executeConcurrentOrders(service, product.id!!, THREAD_COUNT)

    val remaining = getRemainingStock(product.id!!)
    assertOversellOccurred(remaining)  // remaining < INITIAL_STOCK
}
```

#### `LockConcurrentTest`
- `LockOrderService` 사용 시,
  "성공 응답 건수에 해당하는 만큼 재고가 줄었는지"를 기준으로 동작을 검증합니다.

```kotlin
@Test
fun `lock 기반 동시성 테스트 - 재고와 성공 횟수의 합이 초기 재고와 동일해야 한다`() {
    val product = createProduct()
    val service = getLockOrderService()

    val testResult = TestFixture.runConcurrencyTest(service, product.id!!, THREAD_COUNT)

    val remaining = getRemainingStock(product.id!!)
    assertEquals(INITIAL_STOCK, testResult.successCount + remaining)
}
```

#### `PessimisticLockConcurrentTest`
- `PessimisticLockOrderService` 사용 시도 위와 동일한 기준으로 검증합니다.
- 동기화가 **JVM 내부가 아니라 DB 레벨에서 이루어진다**는 점이 다릅니다.

### 6.3 컨트롤러 테스트 (controller/)

#### `OrderControllerTest`
- 정상 주문 요청 → `200 OK` + `success = true`
- `productId` 누락 → `400 BAD_REQUEST` + `VALIDATION_ERROR`
- 재고 부족 상황 → `400 BAD_REQUEST` + `BAD_REQUEST` 에러 코드

```kotlin
@Test
fun `주문 API 정상 요청 시 성공 응답을 반환해야 한다`() {
    val product = TestFixture.createTestProduct(productRepository, "api-test", 10)
    val json = buildOrderRequestJson(product.id!!, 1, "lock")

    mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(json))
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.success").value(true))
}
```

#### `OrderRequestValidationTest`
- `productId`가 없을 때 검증 실패
- `quantity < 1`일 때 검증 실패
- Bean Validation이 의도대로 동작하는지 확인합니다.

### 6.4 테스트 유틸리티 (fixture/)

#### `TestFixture`
테스트 코드의 중복을 제거하기 위한 공통 유틸리티입니다.

```kotlin
object TestFixture {
    // 테스트용 상품 생성
    fun createTestProduct(repository: ProductRepository, name: String, stock: Int): Product
    
    // 동시성 주문 실행
    fun executeConcurrentOrders(service: OrderService, productId: Long, threads: Int)
    
    // 동시성 테스트 실행 및 결과 반환
    fun runConcurrencyTest(service: OrderService, productId: Long, threads: Int): ConcurrencyTestResult
}
```

**설계 원칙**
- 모든 테스트 메서드는 **15줄 이하**로 작성
- given/when/then 구조 명확히 분리
- 중복 로직은 TestFixture로 추출

---

## 7. 동시성 실험 방법 (웹 UI)

서버를 실행한 뒤 브라우저에서 `http://localhost:8080/`에 접속하면,  
**재고 설정 / 재고 조회 / 동시 주문 실험 / 락 개념 데모**를 한 화면에서 수행할 수 있습니다.

### 7.1 동시 주문 실험

1. **재고 설정**
   - 상단에서 초기 재고를 입력하고 **[재고 입력]** 버튼을 눌러 재고를 설정합니다.

2. **재고 조회**
   - **[재고 조회]** 버튼으로 현재 재고를 확인합니다.

3. **동시 주문 실험 설정**
   - 동시 요청 수(threads)를 입력합니다.  
     (실험에서는 각 요청이 **1개씩** 주문한다고 가정합니다.)
   - `no-lock`, `lock(@Synchronized)`, `pessimistic(DB 비관적 락)` 중 하나를 선택합니다.

4. **실험 실행**
   - **[동시 주문 실험 실행]** 버튼을 누르면 결과를 확인할 수 있습니다.

특히 `no-lock` 전략에서는,  
**성공 응답 건수만큼 재고가 줄지 않은 경우**가 나타날 수 있습니다.  
이는 "사용자에게는 두 번 모두 성공이라고 알려줬는데, 실제 재고는 그만큼 줄지 않은" 동시성 문제를 의미합니다.

### 7.2 로컬 락 vs 비관적 락 개념 실험

동일한 화면에서 **[로컬 락 vs 비관적 락 개념 실험]** 버튼을 통해  
`/ui/lock-concept-demo` API를 호출할 수 있습니다.

- 초기 재고를 1개로 두고,
- "서버 인스턴스" 두 개가 동시에 1개씩 주문한다고 가정했을 때

다음과 같이 비교 결과를 보여줍니다.

- **로컬 락 (@Synchronized, 인스턴스 기준)**
  - 성공 응답 건수: 2건
  - 실험 이후 재고: -1개

- **공유 락 (전역 락 = 비관적 락 개념)**
  - 성공 응답 건수: 1건
  - 실험 이후 재고: 0개

이를 통해,

- 로컬 락은 인스턴스마다 락이 따로라서, 인스턴스를 나누면 동시성 문제가 다시 나타날 수 있고,
- 비관적 락처럼 **공유 자원(DB row)**에 락을 거는 방식은  
  여러 인스턴스가 있어도 "한 번에 하나만 들어갈 수 있게 만드는 전략"이라는 점을 직관적으로 확인할 수 있습니다.

---

## 8. 코드 품질 및 리팩터링

### 8.1 우아한테크코스 피드백 적용

본 프로젝트는 우아한테크코스의 공통 피드백을 적극 반영했습니다.

#### ✅ 적용한 원칙

1. **한 메서드가 한 가지 기능만 담당**
   - 모든 public/private 메서드를 **15줄 이하**로 제한
   - 긴 메서드는 여러 개의 작은 메서드로 분리

2. **비즈니스 로직과 UI 로직 분리**
   - api 패키지: 컨트롤러만
   - dto 패키지: 요청/응답 객체만
   - service 패키지: 비즈니스 로직만

3. **객체는 객체답게 사용**
   - `Map<String, Any>` 대신 타입 안전한 `OrderResponse` DTO 사용
   - getter로 데이터를 꺼내지 않고 객체에 메시지 전달

4. **테스트 코드도 코드다**
   - 테스트 메서드도 15줄 규칙 적용
   - TestFixture로 중복 제거
   - given/when/then 구조 명확히

5. **패키지 구조 명확화**
   - 기능별로 패키지 분리 (api, dto, service, domain, repository)
   - 테스트도 목적별로 분리 (controller, concurrency, fixture)

### 8.2 리팩터링 히스토리

총 **10개의 리팩터링 커밋**을 통해 코드 품질을 개선했습니다.

1. DTO와 데모 서비스 클래스를 별도 파일로 분리
2. UiController.runExperiment 메서드 함수 추출 (70줄→15줄)
3. UiController.lockConceptDemo 메서드 함수 추출 (70줄→15줄)
4. ConcurrencyExperimentController.runExperiment 메서드 함수 추출 (60줄→15줄)
5. 동시성 실험 실행 로직을 별도 서비스로 분리
6. OrderService의 응답 타입을 Map에서 DTO로 변경
7. OrderService 선택 로직을 별도 컴포넌트로 분리
8. DTO 클래스를 별도 dto 패키지로 분리
9. 테스트 패키지 구조 개선
10. 테스트 코드에 15줄 규칙 적용 및 TestFixture 분리

---

## 9. 향후 확장 계획

현재는 **단일 인스턴스 환경 + 로컬 락 + DB 비관적 락** 수준에 집중했습니다.

추후 아래와 같은 확장을 고려할 수 있습니다.

### 9.1 기술적 확장

- **낙관적 락 (Optimistic Lock)** 추가
  - `@Version`을 이용한 버전 기반 동시성 제어
  - 충돌 재시도 로직 구현

- **Redis 분산 락 (Redisson)** 추가
  - 다중 인스턴스 환경 대응
  - Pub/Sub 기반 락 해제 알림

- **데이터베이스 락 튜닝**
  - 타임아웃 설정
  - 데드락 감지 및 복구
  - 락 대기 시간 모니터링

### 9.2 모니터링 및 운영

- **메트릭 수집**
  - 동시성 실패율 추적
  - 락 대기 시간 분포
  - 재고 오차 모니터링

- **부하 테스트**
  - JMeter/Gatling을 이용한 성능 테스트
  - 다양한 동시 사용자 시나리오

- **로그 개선**
  - 동시성 문제 발생 시 상세 로그
  - 트랜잭션 추적 (Spring Cloud Sleuth)

### 9.3 기능 확장

- **주문 취소 기능**
  - 재고 복구 로직
  - 취소 시 동시성 제어

- **재고 예약 시스템**
  - 장바구니 담기 시 임시 예약
  - 타임아웃 후 자동 해제

- **재고 알림 기능**
  - 재고 부족 시 알림
  - 재입고 알림

---

## 10. 이 프로젝트에서 배운 점

- 단순한 재고 감소 로직도 동시성 환경에서는 쉽게 깨질 수 있다는 점
- 동시성 문제를 해결할 때, "성공 응답을 몇 번 보냈는지"와  
  "실제로 재고가 얼마나 줄었는지"가 항상 일치해야 한다는 **일관성 규칙**을 세우는 것이 중요하다는 점
- Kotlin의 `@Synchronized`, JPA의 비관적 락, Spring Boot의 테스트 환경, Bean Validation, MockMvc를 한 번에 경험했다는 점
- 단일 인스턴스에서는 로컬 락만으로도 충분해 보일 수 있지만,  
  다중 인스턴스를 고려하면 DB 락/분산 락 등 **공유 자원 기반의 동시성 전략**이 필요하다는 점
- **작은 함수로 나누면 테스트하기 쉽고, 읽기 쉽고, 수정하기 쉽다**는 점
- **테스트 코드도 프로덕션 코드만큼 중요하고, 같은 품질 기준을 적용해야 한다**는 점

---

## 11. 기술 스택

- **언어**: Kotlin 1.9.25
- **프레임워크**: Spring Boot 3.4.1
- **데이터베이스**: H2 (in-memory)
- **ORM**: Spring Data JPA
- **테스트**: JUnit 5, MockMvc, Spring Boot Test
- **빌드**: Gradle 9.2.1

---

## 12. 라이선스

이 프로젝트는 학습 목적으로 작성되었습니다.
