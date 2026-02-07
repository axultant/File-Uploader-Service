package kaspi.lab.uploadService;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Тест производительности для Upload Service.
 *
 * Требования для запуска:
 *   1. docker-compose up -d (все сервисы должны быть запущены)
 *   2. mvn test -pl uploadService -Dtest=FileUploadPerformanceTest
 *
 * Тест отключён по умолчанию (@Disabled), чтобы не запускаться в CI.
 * Для запуска удалите @Disabled или используйте -Dtest=FileUploadPerformanceTest.
 */
@Disabled("Запускать вручную при работающей инфраструктуре (docker-compose up)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileUploadPerformanceTest {

    private static final String BASE_URL = "http://localhost:8081";
    private static final String UPLOAD_PATH = "/api/v1/files/upload";

    private WebClient webClient;
    private final AtomicInteger keyCounter = new AtomicInteger(0);

    @BeforeAll
    void setUp() {
        webClient = WebClient.builder()
                .baseUrl(BASE_URL)
                .codecs(config -> config.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    // ──────────────────────────────────────────────
    //  Сценарий 1: Разогрев + базовый одиночный запрос
    // ──────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Разогрев: 5 последовательных запросов")
    void warmUp() {
        System.out.println("\n══════════════════════════════════════");
        System.out.println("  РАЗОГРЕВ (5 запросов)");
        System.out.println("══════════════════════════════════════");

        for (int i = 0; i < 5; i++) {
            sendUploadRequest(1024).block(Duration.ofSeconds(30));
        }

        System.out.println("  Разогрев завершён.\n");
    }

    // ──────────────────────────────────────────────
    //  Сценарий 2: Последовательные запросы (baseline)
    // ──────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("Baseline: 20 последовательных запросов (1 KB)")
    void sequentialBaseline() {
        int totalRequests = 20;
        int fileSizeBytes = 1024;

        System.out.println("══════════════════════════════════════");
        System.out.println("  BASELINE: последовательные запросы");
        System.out.printf("  Запросов: %d | Размер файла: %s%n", totalRequests, formatSize(fileSizeBytes));
        System.out.println("══════════════════════════════════════");

        List<Long> latencies = new ArrayList<>();
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        long startTime = System.nanoTime();

        for (int i = 0; i < totalRequests; i++) {
            long reqStart = System.nanoTime();
            try {
                sendUploadRequest(fileSizeBytes).block(Duration.ofSeconds(30));
                long elapsed = (System.nanoTime() - reqStart) / 1_000_000;
                latencies.add(elapsed);
                success.incrementAndGet();
            } catch (Exception e) {
                failed.incrementAndGet();
            }
        }

        long totalTime = (System.nanoTime() - startTime) / 1_000_000;
        printReport(latencies, success.get(), failed.get(), totalTime, 1);
    }

    // ──────────────────────────────────────────────
    //  Сценарий 3: Параллельные запросы (10 потоков)
    // ──────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("Нагрузка: 50 запросов, 10 параллельных (1 KB)")
    void concurrentLoad10() {
        runConcurrentTest(50, 10, 1024);
    }

    // ──────────────────────────────────────────────
    //  Сценарий 4: Параллельные запросы (25 потоков)
    // ──────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("Нагрузка: 100 запросов, 25 параллельных (1 KB)")
    void concurrentLoad25() {
        runConcurrentTest(100, 25, 1024);
    }

    // ──────────────────────────────────────────────
    //  Сценарий 5: Параллельные запросы (50 потоков)
    // ──────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("Нагрузка: 200 запросов, 50 параллельных (1 KB)")
    void concurrentLoad50() {
        runConcurrentTest(200, 50, 1024);
    }

    // ──────────────────────────────────────────────
    //  Сценарий 6: Разные размеры файлов
    // ──────────────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("Размеры файлов: 1 KB, 100 KB, 1 MB (10 запросов, concurrency=5)")
    void differentFileSizes() {
        int[] sizes = {1024, 100 * 1024, 1024 * 1024};
        String[] labels = {"1 KB", "100 KB", "1 MB"};

        System.out.println("\n══════════════════════════════════════");
        System.out.println("  РАЗМЕРЫ ФАЙЛОВ: сравнение");
        System.out.println("══════════════════════════════════════");

        for (int i = 0; i < sizes.length; i++) {
            System.out.printf("%n  --- %s ---%n", labels[i]);
            runConcurrentTest(10, 5, sizes[i]);
        }
    }

    // ──────────────────────────────────────────────
    //  Сценарий 7: Стресс-тест
    // ──────────────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("Стресс-тест: 500 запросов, 100 параллельных (1 KB)")
    void stressTest() {
        runConcurrentTest(500, 100, 1024);
    }

    // ══════════════════════════════════════════════
    //  Вспомогательные методы
    // ══════════════════════════════════════════════

    private void runConcurrentTest(int totalRequests, int concurrency, int fileSizeBytes) {
        System.out.printf("%n══════════════════════════════════════%n");
        System.out.printf("  Запросов: %d | Параллельность: %d | Файл: %s%n",
                totalRequests, concurrency, formatSize(fileSizeBytes));
        System.out.println("══════════════════════════════════════");

        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(totalRequests);

        long startTime = System.nanoTime();

        Flux.range(0, totalRequests)
                .flatMap(i -> {
                    long reqStart = System.nanoTime();
                    return sendUploadRequest(fileSizeBytes)
                            .doOnSuccess(body -> {
                                long elapsed = (System.nanoTime() - reqStart) / 1_000_000;
                                latencies.add(elapsed);
                                success.incrementAndGet();
                            })
                            .doOnError(e -> failed.incrementAndGet())
                            .onErrorResume(e -> Mono.empty())
                            .doFinally(s -> latch.countDown());
                }, concurrency)
                .subscribe();

        try {
            boolean completed = latch.await(5, TimeUnit.MINUTES);
            if (!completed) {
                System.out.println("  ПРЕДУПРЕЖДЕНИЕ: таймаут 5 минут истёк!");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long totalTime = (System.nanoTime() - startTime) / 1_000_000;
        printReport(new ArrayList<>(latencies), success.get(), failed.get(), totalTime, concurrency);
    }

    private Mono<String> sendUploadRequest(int fileSizeBytes) {
        byte[] content = generateFileContent(fileSizeBytes);
        String idempotencyKey = "perf-test-" + keyCounter.incrementAndGet() + "-" + UUID.randomUUID();

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return "test-file-" + keyCounter.get() + ".bin";
            }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);

        return webClient.post()
                .uri(UPLOAD_PATH)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .header("X-Idempotency-Key", idempotencyKey)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(String.class);
    }

    private byte[] generateFileContent(int sizeBytes) {
        byte[] content = new byte[sizeBytes];
        new Random().nextBytes(content);
        return content;
    }

    private void printReport(List<Long> latencies, int success, int failed, long totalTimeMs, int concurrency) {
        System.out.println();
        System.out.println("  ┌─────────────────────────────────────┐");
        System.out.println("  │         РЕЗУЛЬТАТЫ ТЕСТА            │");
        System.out.println("  ├─────────────────────────────────────┤");
        System.out.printf("  │  Успешных:     %6d               │%n", success);
        System.out.printf("  │  Ошибок:       %6d               │%n", failed);
        System.out.printf("  │  Общее время:  %6d ms            │%n", totalTimeMs);

        if (!latencies.isEmpty()) {
            Collections.sort(latencies);
            long min = latencies.getFirst();
            long max = latencies.getLast();
            double avg = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
            long p50 = percentile(latencies, 50);
            long p95 = percentile(latencies, 95);
            long p99 = percentile(latencies, 99);
            double rps = success > 0 ? (success * 1000.0) / totalTimeMs : 0;

            System.out.println("  ├─────────────────────────────────────┤");
            System.out.println("  │         ЛАТЕНТНОСТЬ (ms)            │");
            System.out.println("  ├─────────────────────────────────────┤");
            System.out.printf("  │  Min:          %6d ms            │%n", min);
            System.out.printf("  │  Max:          %6d ms            │%n", max);
            System.out.printf("  │  Avg:          %6.0f ms            │%n", avg);
            System.out.printf("  │  P50:          %6d ms            │%n", p50);
            System.out.printf("  │  P95:          %6d ms            │%n", p95);
            System.out.printf("  │  P99:          %6d ms            │%n", p99);
            System.out.println("  ├─────────────────────────────────────┤");
            System.out.println("  │         ПРОПУСКНАЯ СПОСОБНОСТЬ     │");
            System.out.println("  ├─────────────────────────────────────┤");
            System.out.printf("  │  RPS:          %6.1f req/s         │%n", rps);
            System.out.printf("  │  Concurrency:  %6d               │%n", concurrency);
        }

        System.out.println("  └─────────────────────────────────────┘");
        System.out.println();
    }

    private long percentile(List<Long> sortedLatencies, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sortedLatencies.size()) - 1;
        return sortedLatencies.get(Math.max(0, index));
    }

    private String formatSize(int bytes) {
        if (bytes >= 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        if (bytes >= 1024) return (bytes / 1024) + " KB";
        return bytes + " B";
    }
}
