#!/bin/bash

URL="http://localhost:8080/orders"
PRODUCT_ID=${1:-1}
REQUESTS=${2:-200}

echo "no-lock 방식으로 ${REQUESTS}번 주문 요청을 보냅니다. (productId=${PRODUCT_ID})"

for i in $(seq 1 $REQUESTS); do
  curl -s -X POST "$URL" \
    -H "Content-Type: application/json" \
    -d "{\"productId\":${PRODUCT_ID},\"quantity\":1,\"method\":\"no-lock\"}" \
    > /dev/null &
done

wait
echo "완료되었습니다."
