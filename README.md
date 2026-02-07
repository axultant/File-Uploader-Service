# RequestGuard

Микросервисная система модерации клиентских обращений с использованием Kafka, MongoDB и Redis.

## Архитектура

```
                                    +------------------+
                                    |     Service-2    |
                                    |   (Data Service) |
                                    |    Redis :8082   |
                                    +--------^---------+
                                             |
                                             | REST API
                                             | GET /api/clients/{id}
                                             |
+----------+      +------------------+       |        +------------------+
|  Kafka   |----->|    Service-1     |-------+------->|      Kafka       |
| Topic-1  |      |   (Moderation)   |                |     Topic-2      |
| (входящие|      | MongoDB :8081    |                | (одобренные      |
| события) |      +------------------+                |  обращения)      |
+----------+                |                         +------------------+
                            |
                            v
                    +---------------+
                    |    MongoDB    |
                    | (идемпотент-  |
                    |  ность)       |
                    +---------------+
```

### Стек технологий

- **Java 17**, **Spring Boot 4.0.2**, **Spring WebFlux** (реактивный стек)
- **Reactor Kafka** — реактивный Kafka consumer/producer
- **Spring Data MongoDB Reactive** — реактивный доступ к MongoDB
- **Spring Data Redis Reactive** — реактивный доступ к Redis
- **Lombok** — сокращение boilerplate-кода
- **Docker Compose** — оркестрация контейнеров

### Инфраструктура

| Сервис   | Образ              | Порт  | Назначение                               |
|----------|--------------------|-------|------------------------------------------|
| Kafka    | apache/kafka:3.9.0 | 9092  | Очередь сообщений (KRaft mode, без Zookeeper) |
| MongoDB  | mongo:7.0          | 27017 | Хранение обработанных событий            |
| Redis    | redis:7-alpine     | 6379  | Кэш данных клиентов                     |

---

## Service-1: Moderation Service (порт 8081)

Основной сервис модерации обращений. Реактивный (WebFlux + Reactor Kafka).

Путь: `service-1-moderation/`

| Компонент              | Файл                                  | Описание                                              |
|------------------------|---------------------------------------|-------------------------------------------------------|
| KafkaConsumerService   | service/KafkaConsumerService.java     | Слушает topic-1, передаёт события на обработку        |
| ModerationService      | service/ModerationService.java        | Оркестратор: идемпотентность -> обогащение -> правила -> публикация |
| EnrichmentService      | service/EnrichmentService.java        | Вызывает Service-2 для получения данных клиента       |
| ModerationRulesService | service/ModerationRulesService.java   | Бизнес-правила модерации                              |
| KafkaProducerService   | service/KafkaProducerService.java     | Отправляет одобренные обращения в topic-2             |
| ModerationController   | controller/ModerationController.java  | REST API для тестирования (POST /api/moderation/send) |
| ProcessedEvent         | model/ProcessedEvent.java             | MongoDB-документ для хранения обработанных событий    |

---

## Service-2: Data Service (порт 8082)

Сервис данных клиентов. Хранит информацию в Redis.

Путь: `service-2-data/`

| Компонент            | Файл                                 | Описание                        |
|----------------------|--------------------------------------|---------------------------------|
| ClientDataController | controller/ClientDataController.java | REST API: GET/POST /api/clients |
| ClientDataService    | service/ClientDataService.java       | CRUD операции с Redis           |

---

## Поток обработки события

### 1. Получение события (KafkaConsumerService)

```java
@PostConstruct
public void startConsuming() {
    KafkaReceiver.create(receiverOptions)
        .receive()
        .flatMap(record -> moderationService.processEvent(record.value())
            .doOnSuccess(v -> record.receiverOffset().acknowledge())
        )
        .subscribe();
}
```

- Consumer стартует при запуске приложения (`@PostConstruct`)
- События обрабатываются параллельно (`flatMap`, до 256 одновременно)
- Offset подтверждается только после успешной обработки
- При ошибке — событие будет переотправлено Kafka

### 2. Проверка идемпотентности (ModerationService)

```java
public Mono<Void> processEvent(RequestEvent event) {
    return processedEventRepository.findByEventId(event.getEventId())
        .hasElement()
        .flatMap(alreadyProcessed -> {
            if (alreadyProcessed) {
                return Mono.empty(); // Пропускаем дубликат
            }
            return enrichAndModerate(event);
        });
}
```

- Проверка по `eventId` в MongoDB
- Уникальный индекс на `eventId` гарантирует один вызов = одна запись

### 3. Обогащение данными (EnrichmentService)

```java
public Mono<ClientInfo> getClientInfo(String clientId) {
    return webClient.get()
        .uri("/api/clients/{clientId}", clientId)
        .retrieve()
        .bodyToMono(ClientInfo.class)
        .retryWhen(Retry.backoff(3, Duration.ofMillis(500)))
        .onErrorResume(e -> Mono.just(new ClientInfo())); // Fallback
}
```

- Запрос в Service-2 по REST
- Retry с экспоненциальной задержкой (500ms, 1s, 2s)
- При полной недоступности — возвращает пустой объект (обращение не блокируется)

### 4. Применение правил модерации (ModerationRulesService)

```java
public String check(RequestEvent event, ClientInfo clientInfo) {
    // Правило 1: Дубликат активного обращения
    if (hasActiveRequestForSameTopic(event, clientInfo)) {
        return "У клиента уже есть активное обращение...";
    }

    // Правило 2: Рабочее время для LOAN, CARD_ISSUE, MORTGAGE
    if (isOutsideWorkingHours(event)) {
        return "Обращение поступило вне рабочего времени...";
    }

    return null; // Все проверки пройдены
}
```

Правила:

| #  | Правило                           | Категории                  | Результат |
|----|-----------------------------------|----------------------------|-----------|
| 1  | Дубликат по category + topic      | Все                        | REJECTED  |
| 2  | Вне рабочего времени (9:00-18:00) | LOAN, CARD_ISSUE, MORTGAGE | REJECTED  |

### 5. Публикация результата

Если **APPROVED**:

```java
return producerService.send(result)
    .then(saveProcessedEvent(event, "APPROVED", null));
```

Если **REJECTED**:

```java
return saveProcessedEvent(event, "REJECTED", rejectReason);
// В topic-2 НЕ отправляется!
```

---

## Структура данных

### RequestEvent (входящее событие)

```json
{
    "eventId": "evt-123",
    "clientId": "client-1",
    "category": "LOAN",
    "topic": "Досрочное погашение",
    "message": "Хочу погасить кредит досрочно",
    "timestamp": "2024-02-05T14:30:00"
}
```

### ClientInfo (данные из Service-2)

```json
{
    "clientId": "client-1",
    "clientName": "Иван Иванов",
    "activeRequests": [
        {
            "category": "LOAN",
            "topic": "Досрочное погашение",
            "status": "ACTIVE"
        }
    ]
}
```

### ModerationResult (исходящее в topic-2)

```json
{
    "eventId": "evt-123",
    "clientId": "client-1",
    "category": "LOAN",
    "topic": "Досрочное погашение",
    "status": "APPROVED",
    "message": "Хочу погасить кредит досрочно"
}
```

### ProcessedEvent (MongoDB)

```json
{
    "_id": "ObjectId",
    "eventId": "evt-123",
    "clientId": "client-1",
    "status": "APPROVED",
    "rejectReason": null,
    "processedAt": "2024-02-05T14:30:05"
}
```

---

## Запуск

### 1. Запуск инфраструктуры

```bash
docker-compose up -d
```

### 2. Запуск Service-2 (Data Service)

```bash
cd service-2-data
./mvnw spring-boot:run
```

### 3. Запуск Service-1 (Moderation Service)

```bash
cd service-1-moderation
./mvnw spring-boot:run
```

---

## Тестирование

### Ручное тестирование (Postman/curl)

**1. Создать клиента в Service-2:**

```bash
curl -X POST http://localhost:8082/api/clients \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "client-1",
    "clientName": "Тест Клиент",
    "activeRequests": []
  }'
```

**2. Отправить обращение на модерацию:**

```bash
curl -X POST http://localhost:8081/api/moderation/send \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-001",
    "clientId": "client-1",
    "category": "DEPOSIT",
    "topic": "Консультация",
    "message": "Вопрос по вкладу",
    "timestamp": "2024-02-05T14:30:00"
  }'
```

### Нагрузочное тестирование (k6)

```bash
k6 run k6-test.js
```

Сценарий:

- Создание 10 тестовых клиентов (некоторые с активными обращениями)
- Нагрузка 100 RPS в течение 1 минуты

Пороги:

- 95% запросов < 500мс
- Ошибок < 5%
- 99% отправок < 1с

---

## Гарантии

| Свойство             | Механизм                                                    |
|----------------------|-------------------------------------------------------------|
| Идемпотентность      | Уникальный индекс `eventId` в MongoDB                       |
| At-least-once delivery | Manual offset commit после успешной обработки              |
| Отказоустойчивость   | Retry с экспоненциальной задержкой при вызове Service-2     |
| Graceful degradation | Fallback на пустой `ClientInfo` при недоступности Service-2 |
