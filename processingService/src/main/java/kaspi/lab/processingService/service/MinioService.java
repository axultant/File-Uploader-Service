package kaspi.lab.processingService.service;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.UploadObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinioService {

    private final MinioClient minioClient;

    @Value("${app.minio.bucket:uploads}")
    private String bucket;

    // Метод возвращает Mono<String> - это будет путь к файлу в MinIO
    public Mono<String> uploadFile(Path filePath, String contentType, String objectName) {
        return Mono.fromCallable(() -> {
            // 1. Проверяем, есть ли бакет, если нет - создаем
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }

            // 2. Загружаем файл
            log.info("Uploading file to MinIO: bucket={}, object={}", bucket, objectName);
            minioClient.uploadObject(
                    UploadObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectName) // Имя файла в MinIO (обычно UUID)
                            .filename(filePath.toString()) // Путь к файлу на диске
                            .contentType(contentType)
                            .build());

            return objectName;
        }).subscribeOn(Schedulers.boundedElastic()); // Выполняем в отдельном пуле потоков
    }
}