package kaspi.lab.uploadService.service;

import kaspi.lab.uploadService.dto.request.FileUploadRequest;
import kaspi.lab.uploadService.dto.response.FileUploadResponse;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;

public interface UploadService {
    Mono<FileUploadResponse> processUpload(FilePart filePart, FileUploadRequest request, String idempotencyKey);
}
