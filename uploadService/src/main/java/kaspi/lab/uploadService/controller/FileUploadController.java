package kaspi.lab.uploadService.controller;

import jakarta.validation.constraints.NotBlank;
import kaspi.lab.uploadService.dto.request.FileUploadRequest;
import kaspi.lab.uploadService.dto.response.FileUploadResponse;
import kaspi.lab.uploadService.service.UploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Objects;

@Slf4j
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileUploadController {

    private final UploadService uploadService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<FileUploadResponse> uploadFile(
            @RequestPart("file") Mono<FilePart> filePartMono,
            @RequestHeader("X-Idempotency-Key") @NotBlank String idempotencyKey
            ) {
        return filePartMono
                .flatMap(filePart -> {
                    log.info("Received upload request for file: {} with key: {}", filePart.filename(), idempotencyKey);

                    FileUploadRequest request = FileUploadRequest.builder()
                            .filename(filePart.filename())
                            .contentType(filePart.headers().getContentType() != null ? Objects.requireNonNull(filePart.headers().getContentType()).toString() : "application/octet-stream")
                            .build();

                    return uploadService.processUpload(filePart, request, idempotencyKey);
                });
    }
}
