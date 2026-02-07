# File Uploader

Микросервисная система загрузки файлов с асинхронной обработкой через Kafka и хранением в MinIO.

## Архитектура

```
Клиент (Postman)
    |
    v
+------------------+     Kafka      +--------------------+     MinIO
|  Upload Service  | ------------->| Processing Service  | ----------> S3-хранилище
|     :8081        |               |      :8082          |
+--------+---------+               +---------+----------+
         |                                   |
         +---------- PostgreSQL <------------+
                       :5432
```

### Стек технологий

- **Java 21**, **Spring Boot 4.0.2**, **Spring WebFlux** (реактивный стек)
- **R2DBC** — реактивный доступ к PostgreSQL
- **Apache Kafka** (KRaft mode) — очередь событий
- **MinIO** — S3-совместимое объектное хранилище
- **Redis** — хранение ключей идемпотентности
- **MapStruct** — маппинг DTO
- **Docker Compose** — оркестрация контейнеров

### Инфраструктура

| Сервис     | Образ                  | Порт       | Назначение                        |
|------------|------------------------|------------|-----------------------------------|
| PostgreSQL | postgres:16-alpine     | 5432       | Общая БД для обоих сервисов       |
| Redis      | redis:7-alpine         | 6379       | Ключи идемпотентности             |
| Kafka      | apache/kafka:latest    | 9092       | Очередь событий                   |
| MinIO      | minio/minio            | 9000, 9001 | Объектное хранилище (API, Console) |

Сервисы связаны через Docker volume `shared_uploads` для передачи временных файлов.

---

## Upload Service (порт 8081)

Принимает файлы от клиента, сохраняет на диск и в БД, публикует событие через Outbox Pattern.

### Endpoint

```
POST /api/v1/files/upload
Content-Type: multipart/form-data
Header: X-Idempotency-Key: <уникальный ключ>
Body: file=<файл>
```

Ответ (HTTP 202):
```json
{
  "fileId": "uuid",
  "filename": "example.txt",
  "status": "PENDING",
  "message": null,
  "timestamp": "2026-02-02T11:41:00Z"
}
```

### Структура

| Класс                    | Назначение                                                              |
|--------------------------|-------------------------------------------------------------------------|
| `FileUploadController`   | REST-контроллер, принимает multipart-запрос и заголовок идемпотентности  |
| `UploadServiceImpl`      | Сохраняет файл на диск, записывает `FileEntity` и `OutboxEntity` в БД  |
| `FileMapper`             | MapStruct-маппер: Request -> Entity -> Response -> Event                |
| `OutboxRelay`            | Scheduler (каждые 5 сек), читает NEW записи из outbox, отправляет в Kafka |
| `AppUploadProperties`    | Конфигурация: temp-path, idempotency-ttl                               |
| `FileRepository`         | R2DBC-репозиторий для таблицы `files`                                   |
| `OutboxRepository`       | R2DBC-репозиторий для таблицы `outbox`                                  |

### Модель данных

**Таблица `files`:**

| Поле              | Тип                      | Описание                   |
|-------------------|--------------------------|----------------------------|
| id                | UUID (PK)                | Уникальный ID файла        |
| idempotency_key   | VARCHAR(255) UNIQUE      | Ключ идемпотентности       |
| filename          | VARCHAR(255)             | Имя файла                  |
| content_type      | VARCHAR(100)             | MIME-тип                   |
| size              | BIGINT                   | Размер файла               |
| status            | VARCHAR(20)              | PENDING / COMPLETED / FAILED |
| storage_path      | VARCHAR(512)             | Путь к файлу (temp или minio://) |
| created_at        | TIMESTAMP WITH TIME ZONE | Дата создания              |

**Таблица `outbox`:**

| Поле       | Тип                      | Описание                    |
|------------|--------------------------|------------------------------|
| id         | BIGSERIAL (PK)           | Auto-increment ID            |
| event_type | VARCHAR(50)              | Тип события (FILE_UPLOADED)  |
| payload    | TEXT                     | JSON с данными события       |
| status     | VARCHAR(20)              | NEW / PROCESSED              |
| created_at | TIMESTAMP WITH TIME ZONE | Дата создания                |

### Outbox Pattern

Вместо прямой отправки в Kafka из бизнес-логики, событие сначала сохраняется в таблицу `outbox` в одной транзакции с основными данными. Отдельный scheduler (`OutboxRelay`) периодически читает новые записи и отправляет их в Kafka. Это гарантирует доставку события даже при временной недоступности Kafka.

---

## Processing Service (порт 8082)

Слушает Kafka, загружает файлы из временного хранилища в MinIO, обновляет статус в БД.

### Структура

| Класс                   | Назначение                                                         |
|-------------------------|--------------------------------------------------------------------|
| `FileProcessingConsumer`| Kafka listener, оркестрирует обработку файла                       |
| `MinioService`          | Загрузка файлов в MinIO (бакет создаётся автоматически)            |
| `MinioConfig`           | Конфигурация MinioClient (endpoint, credentials)                   |
| `FileRepository`        | R2DBC-репозиторий для обновления статуса файла                     |
| `FileUploadedEvent`     | DTO входящего Kafka-события                                        |

### Логика обработки

1. Получает JSON-сообщение из topic `file-uploaded-topic`
2. Десериализует в `FileUploadedEvent` (fileId, tempPath, filename, contentType, size)
3. Читает файл по `tempPath` из shared volume
4. Загружает в MinIO (bucket: `uploads`, object name: UUID)
5. Обновляет `FileEntity` в БД: status -> `COMPLETED`, storagePath -> `minio://{uuid}`
6. Удаляет временный файл с диска
7. При ошибке: status -> `FAILED`

---

## Жизненный цикл файла

```
1.  POST /api/v1/files/upload + файл + X-Idempotency-Key
2.  Файл записывается на диск: /tmp/file-uploader/uploads/{uuid}
3.  FileEntity сохраняется в PostgreSQL (status: PENDING)
4.  OutboxEntity сохраняется в PostgreSQL (status: NEW, payload: JSON)
5.  HTTP 202 возвращается клиенту
        --- асинхронно (каждые 5 сек) ---
6.  OutboxRelay читает NEW-записи из outbox
7.  Payload отправляется в Kafka topic "file-uploaded-topic"
8.  Outbox status меняется на PROCESSED
        --- в Processing Service ---
9.  KafkaListener получает сообщение
10. Файл с диска загружается в MinIO (bucket: uploads, object: uuid)
11. FileEntity status -> COMPLETED, storagePath -> minio://uuid
12. Временный файл удаляется
```

---

## Запуск

### Сборка

```bash
cd uploadService && ./mvnw clean package -DskipTests && cd ..
cd processingService && ./mvnw clean package -DskipTests && cd ..
```

### Запуск через Docker Compose

```bash
docker-compose up --build -d
```

### Проверка

```bash
curl -X POST http://localhost:8081/api/v1/files/upload \
  -H "X-Idempotency-Key: test-001" \
  -F "file=@myfile.txt"
```

MinIO Console доступна по адресу http://localhost:9001 (логин: `admin`, пароль: `password`).

