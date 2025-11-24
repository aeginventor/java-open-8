# 동시성 안전 구매 API
> Kotlin + Spring Boot 기반 재고 감소 API 구현

## 1. 프로젝트 개요

본 프로젝트는 **주문/구매 과정에서 발생하는 동시성 문제**를 직접 재현하고,  
이를 **Kotlin + Spring Boot** 기반의 재고 관리 API로 해결해 보는 것을 목표로 합니다.

실제 서비스에서는 다음과 같은 문제가 자주 발생합니다.

- 한정 수량 상품에 여러 사용자가 동시에 요청을 보낼 때
- 재고가 음수가 되거나, 초과 주문이 발생하는 문제
- 동시성 버그가 로그나 모니터링 없이 조용히 쌓였다가 장애로 이어지는 문제

이 프로젝트에서는 다음을 경험합니다.

- 단순한 재고 차감 로직으로 **동시성 이슈를 직접 재현**해 보고
- 서로 다른 동시성 제어 전략을 적용해 **설계/테스트 관점에서 어떤 차이가 나는지** 확인합니다.
- 이 과정에서 **Kotlin 문법**, **Spring Boot 구조**, **테스트 코드**를 함께 익힙니다.

---

## 2. 현재 구현 범위

### 2.1 동시성 시나리오

- 하나의 `Product` 엔티티가 있고, `stock` 필드로 재고를 관리합니다.
- 여러 스레드(또는 여러 HTTP 요청)가 동시에 "재고 1 감소" 요청을 보냅니다.
- 이 때, 구현 방식에 따라 다음과 같은 문제가 발생하거나 방지됩니다.

### 2.2 동시성 제어 전략 (현재 구현)

현재는 **세 가지 전략**을 구현했습니다.

1. **NoLockOrderService (무락, baseline)**
  - 아무런 동기화 없이 단순히 재고를 조회하고 차감하는 서비스
  - 멀티스레드 환경에서 오버셀(재고 음수 또는 비정상 값) 가능성이 있습니다.
  - 목적: **“동시성 버그를 재현하는 용도”**

2. **LockOrderService (`@Synchronized` 기반, 로컬 락)**
  - Kotlin의 `@Synchronized`를 사용해 `order()` 메서드 전체를 직렬화합니다.
  - 한 번에 하나의 스레드만 주문 로직을 수행할 수 있도록 **JVM 내부에서** 강하게 동기화합니다.
  - 단일 인스턴스 환경에서는 동시성 문제를 충분히 막을 수 있지만,  
    인스턴스가 여러 대인 환경에서는 각 인스턴스마다 락이 따로 존재한다는 한계가 있습니다.

3. **PessimisticLockOrderService (DB 비관적 락 기반)**
  - JPA의 `@Lock(LockModeType.PESSIMISTIC_WRITE)`를 사용하여  
    **데이터베이스 레벨에서 행(row)에 락을 거는 방식**입니다.
  - 같은 상품 행을 갱신하려는 트랜잭션끼리는 DB에서 순서를 보장합니다.
  - 여러 인스턴스가 같은 DB를 사용해도, **DB 차원에서 동시성을 제어**할 수 있습니다.

> 세 서비스는 모두 `OrderService` 인터페이스를 구현하며,  
> `OrderRequest.method` 값(`"no-lock"`, `"lock"`, `"pessimistic"`)에 따라 어떤 전략을 사용할지 선택할 수 있습니다.

---

## 3. API 명세

### 3.1 주문 생성 API

`POST /orders`

**Request 본문**

```json
{
  "productId": 1,
  "quantity": 1,
  "method": "lock"
}
```
- `productId` (Long, 필수): 주문할 상품 ID
- `quantity` (Int, 최소 1): 주문 수량
- `method` (String, 선택): "no-lock", "lock", "pessimistic", 기본값 "lock"

**Response(성공 예시)**
```
{
  "success": true,
  "remainingStock": 97,
  "method": "lock"
}
```
**Response(실패 예시 - 재고 부족)**
```
{
"success": false,
"error": "BAD_REQUEST",
"detail": "재고 부족"
}
```
- 예외 응답은 GlobalExceptionHandler에서 JSON 형태로 공통 처리합니다.

### 3.2 상품 재고 조회 API

`GET /products/{id}`

**Response 예시**
```
{
  "id": 1,
  "name": "sample-product",
  "stock": 80
}
```
- 동시성 테스트/부하 테스트 이후, 실제 재고 상태를 확인하는 용도로 사용합니다.

## 4. 실행 방법
### 4.1 빌드 및 실행
```declarative
./gradlew clean build
./gradlew bootRun
```
서버는 기본적으로 http://localhost:8080 에서 실행됩니다.

## 5. 테스트 구성
테스트는 위험 영역에 집중하여 다음 세 가지 수준으로 구성했습니다.

### 5.1 도메인 레벨 동시성 테스트

- NoLockConcurrentTest
  - NoLockOrderService에서 멀티스레드 주문을 수행해,
    재고가 비정상적으로 변경될 수 있음을 재현합니다. 
- LockConcurrentTest
  - LockOrderService 사용 시
  - 성공한 주문 수 + 남은 재고 = 초기 재고 라는 불변식이 항상 유지되는지 검증합니다.
  - 동시성 설계의 옳고 그름을 단순 수치가 아니라 도메인 규칙(불변식)으로 확인합니다.
- PessimisticLockConcurrentTest
  - PessimisticLockOrderService 사용 시,
  - 위와 동일한 불변식이 유지되는지 검증합니다.
  - 구현 관점에서는 LockOrderService와 결과가 비슷해 보이지만,
    **동기화가 JVM 내부가 아니라 DB 레벨에서 이루어진다**는점이 다릅니다

### 5.2 DTO 유효성 검증 테스트
- OrderRequestValidationTest
  - productId가 없을 때 검증 실패
  - quantity < 1일 때 검증 실패
  - Bean Validation이 의도대로 동작하는지 확인합니다.

### 5.3 Controller 통합 테스트
- OrderControllerTest
  - 정상 주문 요청 → 200 OK + success = true
  - `productId` 누락 → 400 BAD_REQUEST + VALIDATION_ERROR
  - 재고 부족 상황 → 400 BAD_REQUEST + BAD_REQUEST 에러 코드
이를 통해 “도메인 로직, 입력 레벨, API 레벨”에서 각각의 실패 케이스를 다루고 있음을 보장합니다.

## 6. 동시성 실험 방법 (웹 UI)

서버를 실행한 뒤 브라우저에서 `http://localhost:8080/`에 접속하면  
재고 설정 / 재고 조회 / 동시 주문 실험 / 락 방식 선택을 한 화면에서 수행할 수 있습니다.

1. 상단에서 초기 재고를 입력하고 **[재고 설정]** 버튼을 눌러 재고를 설정합니다.
2. **[재고 조회]** 버튼으로 현재 재고를 확인합니다.
3. 동시 요청 수(threads)를 입력합니다.
4. `no-lock`, `lock(@Synchronized)`, `pessimistic(DB 비관적 락)` 중 하나를 선택하고  
   **[동시 주문 실험 실행]** 버튼을 누르면,
  - 성공/실패 횟수
  - 남은 재고
  - 를 한 눈에 확인할 수 있습니다.

## 7. 향후 확장 계획

현재는 단일 인스턴스 환경 + 로컬 락 + DB 비관적 락 수준에 집중했습니다.
추후 아래와 같은 확장을 고려할 수 있습니다.

- 데이터베이스 비관적 락(Pessimistic Lock) 튜닝 (타임아웃, 데드락 대응 등
- Redis 분산 락(Redisson)을 이용한 다중 인스턴스 환경 대응
- JMeter 등을 이용한 더 정교한 부하/성능 테스트
- 메트릭/로그를 통한 모니터링(동시성 실패 패턴 데이터화)

## 8. 이 프로젝트에서 배운 점
- 단순한 재고 감소 로직도 동시성 환경에서는 쉽게 깨질 수 있다는 점
- 동시성 문제를 해결하는 데 있어, 재고 값 그 자체보다 도메인 불변식(성공 횟수 + 남은 재고 = 초기 재고)을 세우는 것이 효과적일 수 있다는 점
- Kotlin의 @Synchronized, JPA의 비관적 락, Spring Boot의 테스트 환경, Bean Validation, MockMvc를 한 번에 경험
- 단일 인스턴스에서는 로컬 락만으로도 충분해 보이지만, 다중 인스턴스를 고려하면 DB 락/분산 락 등 다른 전략이 필요하다는 점

자세한 회고는 별도 소감문에 정리합니다.